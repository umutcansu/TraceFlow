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

/**
 * Default patterns applied to free-form strings (exception message, stack
 * frames, log text) before shipping. Unlike maskParams — which only checks
 * keys — this scans values for known secret shapes and replaces the
 * sensitive portion with "***". The goal is to catch accidental leaks like
 * `throw new Error("auth failed: Bearer eyJhbGci...")` without turning the
 * stack into noise.
 *
 * Keep the patterns narrow; over-masking hides real bugs.
 */
export const DEFAULT_MESSAGE_MASK_PATTERNS: RegExp[] = [
  // key=value / key: value — mask the value while keeping the key for context.
  /(password|passwd|token|jwt|api[_-]?key|secret|cvv|ssn|pin|auth)\s*[=:]\s*\S+/gi,
  // Authorization headers / Bearer tokens in prose.
  /(authorization|bearer)\s+[A-Za-z0-9._~+/=-]+/gi,
  // JWT shape (three dot-separated base64url segments starting with "eyJ").
  /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g,
];

/**
 * Apply message-level masks. Every match is replaced preserving the leading
 * "key" fragment when obvious (`password=foo` -> `password=***`,
 * `Bearer xyz` -> `Bearer ***`) so engineers still see *what* was masked.
 */
export function maskString(input: string, patterns: RegExp[]): string {
  if (!input) return input;
  let out = input;
  for (const p of patterns) {
    out = out.replace(p, (match) => {
      // Preserve "<key>=" or "<key>: " prefix when present.
      const kv = /^([A-Za-z_][A-Za-z0-9_-]*)\s*([=:])\s*/.exec(match);
      if (kv) return `${kv[1]}${kv[2]}***`;
      const bearer = /^(authorization|bearer)\s+/i.exec(match);
      if (bearer) return `${bearer[0]}***`;
      return "***";
    });
  }
  return out;
}

export function maskStringArray(
  input: string[] | undefined,
  patterns: RegExp[],
): string[] | undefined {
  if (!input) return input;
  return input.map((s) => maskString(s, patterns));
}
