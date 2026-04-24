/**
 * PII masking matches the Android runtime's TraceLog.maskParams behaviour:
 * if any of the patterns matches the *key*, the value is replaced with
 * "***". We never inspect values — matching on values would be a much
 * bigger hammer and routinely mis-fire (e.g. on email-shaped URL params).
 */
export function maskParams(
  params: Record<string, unknown> | undefined,
  patterns: RegExp[],
): Record<string, string> | undefined {
  if (!params) return undefined;
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    const hit = patterns.some((p) => p.test(key));
    out[key] = hit ? "***" : stringify(value);
  }
  return out;
}

function stringify(v: unknown): string {
  if (v == null) return String(v);
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}
