/**
 * Stage 1 acceptance tests for the TraceFlow Babel plugin.
 *
 * Goal of the stage: prove the plugin shell is wired up correctly without
 * actually rewriting source. So most of these tests assert that the output
 * matches the input byte-for-byte, with a few unit tests against the
 * `resolveOptions` and `shouldSkipFile` helpers.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { transformSync } from "@babel/core";
import plugin from "../src/index";
import { resolveOptions } from "../src/options";
import { shouldSkipFile } from "../src/helpers/skip";

/** Convenience: run Babel with our plugin and return the (trimmed) output. */
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

/**
 * Run Babel with no plugins to obtain the "baseline" reprint of `code`.
 * Babel's parser/printer is not strictly byte-preserving (e.g. it expands
 * single-line function bodies onto multiple lines), so to assert that *our*
 * plugin made no changes we compare against this baseline rather than the
 * raw input string.
 */
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

describe("Stage 1: plugin produces no changes", () => {
  // Tests in this block must not depend on NODE_ENV; force it to a known
  // non-production value before each test and restore afterwards.
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("leaves an empty/no-function file untouched", () => {
    const src = "export const x = 1;";
    const out = transform(src, "/abs/project/src/foo.ts");
    expect(out).toBe(baseline(src, "/abs/project/src/baseline.ts"));
  });

  it("does not instrument files matched by excludePatterns", () => {
    const src = "export function f() { return 42; }";
    const out = transform(src, "/abs/project/src/skip.ts", {
      excludePatterns: [/skip\.ts$/],
    });
    expect(out).toBe(baseline(src, "/abs/project/src/baseline.ts"));
  });

  it("does nothing when enabled is false", () => {
    const src = "export function f() { return 42; }";
    const out = transform(src, "/abs/project/src/foo.ts", { enabled: false });
    expect(out).toBe(baseline(src, "/abs/project/src/baseline.ts"));
  });

  it("disables itself by default in production", () => {
    process.env.NODE_ENV = "production";
    const src = "export function f() { return 42; }";
    const out = transform(src, "/abs/project/src/foo.ts");
    expect(out).toBe(baseline(src, "/abs/project/src/baseline.ts"));
  });
});

describe("resolveOptions", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("applies documented defaults for empty input", () => {
    const r = resolveOptions({});
    expect(r.runtimeImport).toBe("@umutcansu/traceflow-runtime");
    expect(r.excludePatterns).toEqual([]);
    expect(r.traceArguments).toBe(true);
    expect(r.enabled).toBe(true);
  });

  it("treats null/undefined like an empty object", () => {
    expect(resolveOptions(undefined).enabled).toBe(true);
    expect(resolveOptions(null).enabled).toBe(true);
  });

  it("defaults enabled to false in production", () => {
    process.env.NODE_ENV = "production";
    expect(resolveOptions({}).enabled).toBe(false);
  });

  it("rejects non-RegExp excludePatterns entries", () => {
    expect(() =>
      resolveOptions({ excludePatterns: ["not a regex"] as unknown }),
    ).toThrow(/excludePatterns/);
  });

  it("rejects non-array excludePatterns", () => {
    expect(() =>
      resolveOptions({ excludePatterns: "nope" as unknown }),
    ).toThrow(/excludePatterns/);
  });

  it("rejects non-boolean traceArguments", () => {
    expect(() =>
      resolveOptions({ traceArguments: "yes" as unknown }),
    ).toThrow(/traceArguments/);
  });

  it("rejects non-boolean enabled", () => {
    expect(() => resolveOptions({ enabled: 1 as unknown })).toThrow(/enabled/);
  });

  it("rejects empty / non-string runtimeImport", () => {
    expect(() => resolveOptions({ runtimeImport: "" })).toThrow(
      /runtimeImport/,
    );
    expect(() =>
      resolveOptions({ runtimeImport: 42 as unknown }),
    ).toThrow(/runtimeImport/);
  });
});

describe("shouldSkipFile", () => {
  const opts = resolveOptions({});

  it("skips when filename is undefined", () => {
    expect(shouldSkipFile(undefined, opts)).toBe(true);
  });

  it("skips paths under node_modules", () => {
    expect(
      shouldSkipFile("/abs/project/node_modules/foo/index.js", opts),
    ).toBe(true);
  });

  it("skips the runtime package itself (resolved path)", () => {
    expect(
      shouldSkipFile(
        "/abs/project/packages/@umutcansu/traceflow-runtime/dist/index.js",
        opts,
      ),
    ).toBe(true);
  });

  it("skips the sibling runtime-js source directory", () => {
    expect(
      shouldSkipFile("/abs/project/runtime-js/src/index.ts", opts),
    ).toBe(true);
  });

  it("does not skip a normal source file", () => {
    expect(shouldSkipFile("/abs/project/src/feature.ts", opts)).toBe(false);
  });
});
