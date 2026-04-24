import type { ResolvedConfig, TraceFlowConfig } from "./config.js";
import type { TraceEvent, TraceEventType } from "./types.js";

import { RingBuffer } from "./buffer.js";
import { resolveConfig } from "./config.js";
import { newSessionId, resolveDeviceId } from "./deviceId.js";
import { installGlobalHandlers } from "./handlers.js";
import { maskParams } from "./masking.js";
import { detectRuntime } from "./platform.js";
import { Sender } from "./sender.js";

export type {
  TraceEvent,
  TraceEventType,
  Platform,
} from "./types.js";
export type { TraceFlowConfig } from "./config.js";

/**
 * Active TraceFlow client. Returned by `initTraceFlow`. Call `shutdown()`
 * to perform a final flush (e.g. on app background).
 */
export interface TraceFlowClient {
  /** Resolved config after defaults applied. */
  readonly config: Readonly<ResolvedConfig>;
  captureException(err: unknown, meta?: { isFatal?: boolean; stack?: string[] }): void;
  trace<T>(name: string, fn: () => T): T;
  traceAsync<T>(name: string, fn: () => Promise<T>): Promise<T>;
  enter(className: string, method: string, params?: Record<string, unknown>): void;
  exit(className: string, method: string, result?: unknown, durationMs?: number): void;
  setUserId(userId: string | null): void;
  /** Force an immediate flush. Safe to await. */
  flush(): Promise<void>;
  shutdown(): Promise<void>;
}

let activeClient: TraceFlowClient | null = null;

/**
 * Initialise the TraceFlow runtime. Returns a client that can also be
 * accessed via the module-level `captureException` / `trace` exports.
 * Calling `initTraceFlow` twice stops the previous client first.
 */
export function initTraceFlow(cfg: TraceFlowConfig): TraceFlowClient {
  // Tear down any existing client first so a hot-reload in dev doesn't
  // stack multiple flush loops.
  if (activeClient) {
    // Fire-and-forget shutdown; the new client proceeds in parallel.
    void activeClient.shutdown();
  }

  const resolved = resolveConfig(cfg);
  const buffer = new RingBuffer<TraceEvent>(resolved.maxBufferSize);
  const sender = new Sender(resolved, buffer, (err) => {
    // Surface transient network errors to the console; the runtime must
    // never throw from the app's perspective.
    // eslint-disable-next-line no-console
    console.warn("[TraceFlow] flush error:", err);
  });

  // deviceId resolution is async on RN; until it lands we use a
  // placeholder. Events emitted in the window before it resolves still
  // ship — just with an "unresolved" deviceId rather than missing.
  const runtimeTag = resolved.runtime || detectRuntime("unknown");
  const sessionId = newSessionId();
  let deviceId = "resolving";
  void resolveDeviceId().then((id) => { deviceId = id; });

  let userIdOverride: string | null | undefined = resolved.userId;

  const baseFields = () => ({
    schemaVersion: 2 as const,
    platform: resolved.platform,
    runtime: runtimeTag,
    appId: resolved.appId,
    appVersion: resolved.appVersion,
    buildNumber: resolved.buildNumber,
    userId: userIdOverride ?? undefined,
    deviceId,
    sessionId,
    ts: Date.now(),
  });

  const push = (partial: Partial<TraceEvent> & { type: TraceEventType }) => {
    buffer.push({ ...baseFields(), ...partial } as TraceEvent);
  };

  const teardownHandlers = installGlobalHandlers((err, ctx) => {
    const e = asError(err);
    push({
      type: "CATCH",
      exception: e.name,
      message: e.message,
      stack: splitStack(e.stack),
      class: "GlobalHandler",
      method: ctx?.isFatal ? "fatal" : "unhandled",
      file: "",
      line: 0,
    });
  });

  sender.start();

  const client: TraceFlowClient = {
    config: Object.freeze({ ...resolved }),

    captureException(err, meta) {
      const e = asError(err);
      push({
        type: "CATCH",
        exception: e.name,
        message: e.message,
        stack: meta?.stack ?? splitStack(e.stack),
        class: "captureException",
        method: meta?.isFatal ? "fatal" : "manual",
        file: "",
        line: 0,
      });
    },

    trace(name, fn) {
      const start = Date.now();
      push({ type: "ENTER", class: "trace", method: name, file: "", line: 0 });
      try {
        const result = fn();
        push({ type: "EXIT", class: "trace", method: name, durationMs: Date.now() - start });
        return result;
      } catch (err) {
        this.captureException(err);
        throw err;
      }
    },

    async traceAsync(name, fn) {
      const start = Date.now();
      push({ type: "ENTER", class: "trace", method: name, file: "", line: 0 });
      try {
        const result = await fn();
        push({ type: "EXIT", class: "trace", method: name, durationMs: Date.now() - start });
        return result;
      } catch (err) {
        this.captureException(err);
        throw err;
      }
    },

    enter(className, method, params) {
      const p = maskParams(params, resolved.maskPatterns);
      push({ type: "ENTER", class: className, method, params: p });
    },

    exit(className, method, result, durationMs) {
      push({
        type: "EXIT",
        class: className,
        method,
        result: result == null ? undefined : String(result),
        durationMs,
      });
    },

    setUserId(userId) {
      userIdOverride = userId;
    },

    flush() { return sender.flush(); },

    async shutdown() {
      teardownHandlers();
      await sender.shutdown();
      if (activeClient === client) activeClient = null;
    },
  };

  activeClient = client;
  return client;
}

// Module-level convenience wrappers so consumers can write e.g.
//   import { captureException } from "@umutcansu/traceflow-runtime";
// without threading the client through their own modules.

export function captureException(err: unknown, meta?: { isFatal?: boolean; stack?: string[] }): void {
  activeClient?.captureException(err, meta);
}

export function trace<T>(name: string, fn: () => T): T {
  if (!activeClient) return fn();
  return activeClient.trace(name, fn);
}

export function traceAsync<T>(name: string, fn: () => Promise<T>): Promise<T> {
  if (!activeClient) return fn();
  return activeClient.traceAsync(name, fn);
}

export function setUserId(userId: string | null): void {
  activeClient?.setUserId(userId);
}

export async function shutdown(): Promise<void> {
  if (activeClient) await activeClient.shutdown();
}

/** Test hook: not for production use. */
export function _getActiveClient(): TraceFlowClient | null {
  return activeClient;
}

// -- helpers ----------------------------------------------------------------

function asError(err: unknown): Error {
  if (err instanceof Error) return err;
  if (typeof err === "string") return new Error(err);
  try { return new Error(JSON.stringify(err)); } catch { return new Error(String(err)); }
}

function splitStack(stack?: string): string[] | undefined {
  if (!stack) return undefined;
  return stack
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
}
