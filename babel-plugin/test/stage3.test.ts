/**
 * Stage 3 acceptance tests for the TraceFlow Babel plugin.
 *
 * Stage 3 wraps `FunctionExpression` and `ArrowFunctionExpression` nodes,
 * resolving their method-name slot from the surrounding context (variable
 * declarators, assignment expressions, object properties). Concise arrow
 * bodies are converted to a block before wrapping.
 *
 * Out of scope (deferred to later stages):
 *  - ClassMethod / ObjectMethod (Stage 4)
 *  - async / generator function expressions (Stage 5)
 *
 * Anonymous wraps are off by default — `arr.map(x => x*2)` does not get
 * traced unless `instrumentAnonymous: true`.
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { transformSync } from "@babel/core";
import * as vm from "node:vm";
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

describe("Stage 3: function-expression / arrow wrapping", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("wraps a concise-arrow assigned to a const, converting body to a block", () => {
    const src = "const sum = (a, b) => a + b;";
    const out = transform(src, "/abs/path/to/math.ts");

    assertContains(
      out,
      '__tf_c?.enter("math", "sum"',
      '__tf_c?.exit("math", "sum"',
      "try {",
      "return a + b;",
      "finally {",
    );
    // Concise body should have been turned into a block; the original
    // `(a, b) => a + b;` must no longer appear verbatim.
    assertNotContains(out, "=> a + b;");
  });

  it("wraps a function expression assigned to a const using the var name", () => {
    const src = 'const greet = function (name) { return "hi " + name; };';
    const out = transform(src, "/abs/path/to/g.ts");

    assertContains(
      out,
      '__tf_c?.enter("g", "greet"',
      '__tf_c?.exit("g", "greet"',
      'return "hi " + name;',
    );
  });

  it("prefers the function expression's own id over the surrounding var name", () => {
    const src = "const handler = function clicked(e) { return e.target; };";
    const out = transform(src, "/abs/path/to/ev.ts");

    assertContains(out, '__tf_c?.enter("ev", "clicked"');
    assertNotContains(out, '"handler"');
  });

  it("uses the property key when an arrow is an object literal value", () => {
    const src = "const obj = { fetch: () => 42 };";
    const out = transform(src, "/abs/path/to/o.ts");

    assertContains(out, '__tf_c?.enter("o", "fetch"');
  });

  it("uses the property key when a function expression is an object value", () => {
    const src = "module.exports = { run: function() { return 1; } };";
    const out = transform(src, "/abs/path/to/m.ts");

    assertContains(out, '__tf_c?.enter("m", "run"');
  });

  it("derives the name from a member-expression assignment LHS", () => {
    const src = "exports.go = () => 1;";
    const out = transform(src, "/abs/path/to/x.ts");

    assertContains(out, '__tf_c?.enter("x", "go"');
  });

  it("does NOT wrap an inline anonymous arrow by default", () => {
    const src = "arr.map(x => x * 2);";
    const out = transform(src, "/abs/path/to/cb.ts");

    assertNotContains(out, "__tf_c", "_getActiveClient");
    expect(out).toBe(baseline(src, "/abs/path/to/cb.ts"));
  });

  it("wraps an inline anonymous arrow when instrumentAnonymous is true", () => {
    const src = "arr.map(x => x * 2);";
    const out = transform(src, "/abs/path/to/cb.ts", {
      instrumentAnonymous: true,
    });

    expect(out).toMatch(/__tf_c\?\.enter\("cb", "<anonymous>:\d+"/);
    assertContains(out, "return x * 2;");
  });

  it("is idempotent across two plugin instances over the same AST", () => {
    const src = "const sum = (a, b) => a + b;";
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
    expect(countOccurrences(out, "_getActiveClient")).toBe(1);
  });

  it("respects @notrace when the comment attaches to the function node", () => {
    // Babel's parser attaches a comment between `=` and the arrow as a
    // leadingComment on the ArrowFunctionExpression itself. Comments on the
    // surrounding `VariableDeclaration` do NOT attach to the inner function;
    // documenting that limitation here so a future change doesn't regress it.
    const src = "const fn = /* @notrace */ () => 1;";
    const out = transform(src, "/abs/path/to/n.ts");

    expect(out).toBe(baseline(src, "/abs/path/to/n.ts"));
    assertNotContains(out, "__tf_c", "_getActiveClient");
  });

  it("respects @notrace on a function expression's leadingComments", () => {
    const src = "const fn = /* @notrace */ function () { return 1; };";
    const out = transform(src, "/abs/path/to/n.ts");

    expect(out).toBe(baseline(src, "/abs/path/to/n.ts"));
    assertNotContains(out, "__tf_c");
  });

  it("omits the params object when traceArguments is false on an arrow", () => {
    const src = "const sum = (a, b) => a + b;";
    const out = transform(src, "/abs/path/to/s.ts", {
      traceArguments: false,
    });

    assertContains(out, '__tf_c?.enter("s", "sum", undefined');
    assertNotContains(out, '__tf_c?.enter("s", "sum", {');
  });

  it("emits exactly one runtime import for a file with several arrows", () => {
    const src = `
      const a = (x) => x;
      const b = (y) => y + 1;
      const c = (z) => z * 2;
    `;
    const out = transform(src, "/abs/path/to/multi.ts");

    expect(countOccurrences(out, "_getActiveClient")).toBe(1);
    expect(countOccurrences(out, "_tf_getClient()")).toBe(3);
    expect(countOccurrences(out, "__tf_c?.enter(")).toBe(3);
  });

  it("preserves `this` inside a wrapped function expression", () => {
    // The wrapper does not introduce an IIFE, so `this` should still bind to
    // the call-site receiver. We compile a tiny module, eval it under a vm
    // sandbox with stub runtime helpers, and assert the `this.x` lookup
    // returns the receiver's value.
    const src = `
      const obj = {
        x: 42,
        getX: function () { return this.x; },
      };
      module.exports = obj.getX.call(obj);
    `;
    const out = transform(src, "/abs/path/to/objfile.ts");

    // Sanity: the function expression really did get wrapped.
    assertContains(out, '__tf_c?.enter("objfile", "getX"');

    // Strip the runtime import (we don't want to resolve a real module under
    // vm) and replace it with stub helpers so the body short-circuits past
    // the optional-chain calls but still executes the original return.
    const stripped = out.replace(
      /^import[^\n]*from\s*"@umutcansu\/traceflow-runtime";\s*/m,
      "",
    );
    const sandbox: Record<string, unknown> = {
      module: { exports: {} as unknown },
      _tf_getClient: () => null,
      __tf_capture: () => undefined,
    };
    vm.runInNewContext(stripped, sandbox);
    expect((sandbox.module as { exports: number }).exports).toBe(42);
  });

  it("wraps async arrow functions as of Stage 5", () => {
    const src = "const f = async () => 1;";
    const out = transform(src, "/abs/path/to/a.ts");

    assertContains(out, "async ()", "__tf_c?.enter", '"f"', "__tf_c?.exit");
  });
});
