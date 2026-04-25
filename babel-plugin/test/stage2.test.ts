/**
 * Stage 2 acceptance tests for the TraceFlow Babel plugin.
 *
 * Stage 2 wraps top-level synchronous, non-generator `FunctionDeclaration`s
 * with an ENTER/CATCH/EXIT prelude/postlude. These tests focus on:
 *
 *  - the marker text the wrapper emits (ENTER, EXIT, try/finally, etc.)
 *  - idempotency under repeated transformation
 *  - opt-outs (`@notrace`, async, generator)
 *  - import injection only when at least one function is wrapped
 *  - param-object construction across simple, default, rest, destructured
 *
 * To stay resilient to whitespace differences from Babel's printer we assert
 * on substring presence rather than exact-string equality. Whenever the
 * "no transformation expected" property matters we compare against a
 * baseline reprint produced with no plugins applied.
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { transformSync } from "@babel/core";
import plugin from "../src/index";

function transform(
  code: string,
  filename: string,
  opts: Record<string, unknown> = {},
): string {
  const result = transformSync(code, {
    filename,
    babelrc: false,
    configFile: false,
    plugins: [[plugin, opts]],
  });
  if (!result || result.code == null) {
    throw new Error("transformSync returned no code");
  }
  return result.code;
}

function baseline(code: string, filename: string): string {
  const result = transformSync(code, {
    filename,
    babelrc: false,
    configFile: false,
    plugins: [],
  });
  if (!result || result.code == null) {
    throw new Error("baseline transformSync returned no code");
  }
  return result.code;
}

function assertContains(out: string, ...markers: string[]): void {
  for (const m of markers) {
    expect(out, `expected output to contain ${JSON.stringify(m)}`).toContain(m);
  }
}

function assertNotContains(out: string, ...markers: string[]): void {
  for (const m of markers) {
    expect(
      out,
      `expected output NOT to contain ${JSON.stringify(m)}`,
    ).not.toContain(m);
  }
}

/** Count non-overlapping occurrences of `needle` in `hay`. */
function countOccurrences(hay: string, needle: string): number {
  if (needle.length === 0) return 0;
  let count = 0;
  let idx = hay.indexOf(needle);
  while (idx !== -1) {
    count += 1;
    idx = hay.indexOf(needle, idx + needle.length);
  }
  return count;
}

describe("Stage 2: function-declaration wrapping", () => {
  // Pin NODE_ENV so the plugin's production-default does not surprise us.
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("wraps a simple named function declaration with ENTER/EXIT/try/finally", () => {
    const src = "function add(a, b) { return a + b; }";
    const out = transform(src, "/abs/path/to/math.ts");

    assertContains(
      out,
      "import {",
      "_getActiveClient as _tf_getClient",
      // captureException is no longer imported — the catch path uses
      // __tf_c?.caught(class, method, err) so the CATCH event is
      // attributed to the originating function instead of the
      // captureException helper.
      '__tf_c?.enter("math", "add"',
      '__tf_c?.exit("math", "add"',
      '__tf_c?.caught("math", "add"',
      "try {",
      "return a + b;",
      "finally {",
      "Date.now()",
      "__tf_t0",
      '"math"',
    );
  });

  it("is idempotent when the plugin runs twice over the same AST", () => {
    // Two plugin instances chained in the same pass share the AST, so the
    // second instance sees the `__tfWrapped` marker the first one set and
    // must not re-wrap. (Re-feeding the printed output would re-parse and
    // lose the marker — that's not the idempotency contract here.)
    const src = "function add(a, b) { return a + b; }";
    const result = transformSync(src, {
      filename: "/abs/path/to/math.ts",
      babelrc: false,
      configFile: false,
      plugins: [
        [plugin, {}, "traceflow-a"],
        [plugin, {}, "traceflow-b"],
      ],
    });
    if (!result || result.code == null) throw new Error("no code");
    const out = result.code;

    expect(countOccurrences(out, "__tf_c?.enter(")).toBe(1);
    expect(countOccurrences(out, "__tf_c?.exit(")).toBe(1);
    expect(countOccurrences(out, "try {")).toBe(1);
    // Only one runtime import even with two plugin instances.
    expect(countOccurrences(out, "_getActiveClient")).toBe(1);
  });

  it("respects @notrace opt-out and skips import injection", () => {
    const src = "/* @notrace */ function add(a, b) { return a + b; }";
    const out = transform(src, "/abs/path/to/math.ts");
    const base = baseline(src, "/abs/path/to/math.ts");

    expect(out).toBe(base);
    assertNotContains(out, "_tf_getClient", "_getActiveClient", "__tf_c");
  });

  it("omits the params object when traceArguments is false", () => {
    const src = "function add(a, b) { return a + b; }";
    const out = transform(src, "/abs/path/to/math.ts", {
      traceArguments: false,
    });

    // ENTER call still present, but with `undefined` instead of {a, b}.
    assertContains(out, '__tf_c?.enter("math", "add", undefined');
    // The ENTER call line must not contain a params-object literal.
    // Match `enter("math", "add", {` — that would indicate args object.
    assertNotContains(out, '__tf_c?.enter("math", "add", {');
  });

  it("wraps async function declarations as of Stage 5", () => {
    // Stage 2 originally skipped async; Stage 5 dropped that guard.
    // The wrap shape is identical to sync — try/finally semantics for
    // async are equivalent, so no body change is needed.
    const src = "async function fetchUser(id) { return null; }";
    const out = transform(src, "/abs/path/to/users.ts");

    assertContains(out, "async function fetchUser", "__tf_c?.enter", '"fetchUser"', "__tf_c?.exit");
  });

  it("does NOT wrap generator function declarations (deferred)", () => {
    const src = "function* gen() { yield 1; }";
    const out = transform(src, "/abs/path/to/gens.ts");
    const base = baseline(src, "/abs/path/to/gens.ts");

    expect(out).toBe(base);
    assertNotContains(out, "__tf_c", "_tf_getClient");
  });

  it("does not inject the import when a file has no wrappable functions", () => {
    const src = "export const x = 1;";
    const out = transform(src, "/abs/path/to/foo.ts");
    expect(out).toBe(baseline(src, "/abs/path/to/foo.ts"));
    assertNotContains(out, "_getActiveClient", "_tf_getClient");
  });

  it("emits exactly one runtime import for files with multiple functions", () => {
    const src = `
      function a(x) { return x; }
      function b(y) { return y + 1; }
    `;
    const out = transform(src, "/abs/path/to/multi.ts");

    // The original imported name appears only inside the import specifier.
    expect(countOccurrences(out, "_getActiveClient")).toBe(1);
    // ...whereas the local alias is referenced once per wrapped function.
    expect(countOccurrences(out, "_tf_getClient()")).toBe(2);
    // And there should be one ENTER per function.
    expect(countOccurrences(out, "__tf_c?.enter(")).toBe(2);
  });

  it("collects identifier names for default + rest params", () => {
    const src = "function f(a, b = 1, ...rest) { return rest; }";
    const out = transform(src, "/abs/path/to/p.ts");

    // The params object passed to ENTER references the actual bound names
    // in the function scope: `a`, `b`, and `rest`.
    assertContains(out, "__tf_c?.enter(", '"f"');
    assertContains(out, "a,");
    assertContains(out, "b,");
    // The rest binding's real in-scope name is `rest`, not `rest_rest`.
    expect(out).toMatch(/\{\s*a,\s*b,\s*rest\s*\}/);
  });

  it("expands destructured object params into their bound identifiers", () => {
    // Regression: 0.1.2 emitted shorthand `{ _destr_0 }` here, which Hermes
    // could not resolve at the call site (the helper only exists inside
    // Babel's destructuring transform). The fix walks the pattern and emits
    // the actual bound names instead.
    const src = "function f({ a, b }, c) { return a + b + c; }";
    const out = transform(src, "/abs/path/to/d.ts");

    // No more `_destr_<i>` references anywhere in the output.
    expect(out).not.toMatch(/_destr_/);
    // ENTER's params object holds the *bound* identifiers `a`, `b`, `c`.
    expect(out).toMatch(/\{\s*a,\s*b,\s*c\s*\}/);
    // Original destructure stays in the function signature.
    assertContains(out, "function f({");
    assertContains(out, "}, c)");
  });

  it("expands renamed object-pattern bindings to the renamed identifier", () => {
    // `{ user: u }` binds `u`, not `user`. The wrapper must reference what
    // is actually in scope.
    const src = "function f({ user: u, settings }) { return u; }";
    const out = transform(src, "/abs/path/to/r.ts");
    expect(out).not.toMatch(/_destr_/);
    expect(out).toMatch(/\{\s*u,\s*settings\s*\}/);
  });

  it("expands array-pattern params into their bound identifiers", () => {
    const src = "function f([a, b, ...rest]) { return a + b; }";
    const out = transform(src, "/abs/path/to/a.ts");
    expect(out).not.toMatch(/_destr_/);
    expect(out).toMatch(/\{\s*a,\s*b,\s*rest\s*\}/);
  });
});
