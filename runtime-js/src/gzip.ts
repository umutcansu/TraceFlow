/**
 * gzip a string, preferring the platform-native CompressionStream (modern
 * browsers, Node ≥18, Bun, and Hermes when flagged) and falling back to
 * the optional `pako` peer dep. If neither is available, returns the
 * input string unchanged so the caller can drop Content-Encoding.
 */
export type GzipResult = { body: Uint8Array; encoded: true } | { body: string; encoded: false };

declare const CompressionStream: {
  new (format: "gzip" | "deflate"): {
    readable: ReadableStream<Uint8Array>;
    writable: WritableStream<Uint8Array>;
  };
} | undefined;

export async function gzipString(input: string): Promise<GzipResult> {
  if (typeof CompressionStream !== "undefined") {
    try {
      const enc = new TextEncoder().encode(input);
      const cs = new CompressionStream("gzip");
      const writer = cs.writable.getWriter();
      writer.write(enc);
      writer.close();
      const buf = await new Response(cs.readable).arrayBuffer();
      return { body: new Uint8Array(buf), encoded: true };
    } catch {
      // fall through to pako
    }
  }
  try {
    // @ts-expect-error: optional peer dependency
    const pako = await import("pako");
    const body: Uint8Array = pako.gzip(input);
    return { body, encoded: true };
  } catch {
    return { body: input, encoded: false };
  }
}
