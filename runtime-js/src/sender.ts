import type { ResolvedConfig } from "./config.js";
import type { TraceEvent } from "./types.js";
import { RingBuffer } from "./buffer.js";
import { gzipString } from "./gzip.js";

/**
 * Owns the flush loop: drains the shared buffer on an interval, POSTs the
 * batch (gzip-compressed when supported), and on failure puts the batch
 * back at the head of the buffer for retry. `shutdown()` performs a final
 * flush and returns a Promise the caller can await.
 */
export class Sender {
  private timer: ReturnType<typeof setInterval> | null = null;
  private flushInFlight: Promise<void> | null = null;
  private stopped = false;

  constructor(
    private readonly cfg: ResolvedConfig,
    private readonly buffer: RingBuffer<TraceEvent>,
    private readonly onError: (err: unknown) => void = () => {},
  ) {}

  start(): void {
    if (this.timer || this.stopped) return;
    // Use an unref'd timer when possible so a Node smoke test can exit
    // cleanly without an explicit shutdown().
    this.timer = setInterval(() => {
      void this.flush();
    }, this.cfg.flushIntervalMs);
    const t = this.timer as unknown as { unref?: () => void };
    t.unref?.();
  }

  async shutdown(): Promise<void> {
    this.stopped = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    // Wait for any in-flight flush, then drain whatever is left.
    if (this.flushInFlight) {
      try { await this.flushInFlight; } catch { /* swallow */ }
    }
    await this.flush(/* finalFlush */ true);
  }

  async flush(finalFlush = false): Promise<void> {
    if (this.flushInFlight) return this.flushInFlight;
    if (this.buffer.length === 0) return;

    const run = async () => {
      // If this is a final flush we loop until empty; otherwise we send
      // exactly one batch per tick so a slow server can't create pile-ups.
      do {
        const batch = this.buffer.drain(this.cfg.batchSize);
        if (batch.length === 0) break;
        try {
          await this.post(batch);
        } catch (err) {
          this.onError(err);
          if (this.cfg.retryOnError && !finalFlush) {
            // Put batch back at the front so order is preserved.
            this.buffer.prepend(batch);
          }
          break;
        }
      } while (finalFlush);
    };

    this.flushInFlight = run().finally(() => {
      this.flushInFlight = null;
    });
    return this.flushInFlight;
  }

  private async post(batch: TraceEvent[]): Promise<void> {
    const bodyJson = JSON.stringify(batch);
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (this.cfg.token) headers["X-TraceFlow-Token"] = this.cfg.token;

    let body: BodyInit;
    if (this.cfg.compress) {
      const gz = await gzipString(bodyJson);
      if (gz.encoded) {
        headers["Content-Encoding"] = "gzip";
        // Uint8Array is a valid BodyInit in all supported runtimes but
        // TS's lib.dom union-narrowing is picky; cast through BufferSource
        // which is the documented accepted shape.
        body = gz.body as BodyInit;
      } else {
        body = gz.body; // string
      }
    } else {
      body = bodyJson;
    }

    const res = await fetch(this.cfg.endpoint, {
      method: "POST",
      headers,
      body,
    });
    if (!res.ok) {
      const text = await safeReadText(res);
      throw new Error(`TraceFlow POST failed: HTTP ${res.status} ${text}`);
    }
  }
}

async function safeReadText(res: Response): Promise<string> {
  try { return await res.text(); } catch { return ""; }
}
