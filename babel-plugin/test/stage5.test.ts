/**
 * Stage 5 acceptance tests for the TraceFlow Babel plugin.
 *
 * Stage 5 extends wrapping to async functions across all three visitor
 * families (declaration, expression/arrow, class/object method). The body
 * shape produced by `buildWrappedBody` is intentionally unchanged: JS
 * try/finally has the same observable semantics for async, so a single
 * delete-the-`node.async` early-return in each visitor is the entire change.
 *
 * Generators (sync and async) remain deferred. Wrapping a generator with the
 * current try/finally shape would interleave with `yield` in ways that need
 * a separate, explicit design pass.
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

/** Strip the runtime import line so we can run output under vm. */
function stripRuntimeImport(out: string): string {
  return out.replace(
    /^import[^\n]*from\s*"@umutcansu\/traceflow-runtime";\s*/m,
    "",
  );
}

describe("Stage 5: async function wrapping", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("wraps an async function declaration and preserves the async keyword + body", () => {
    const src = `
      async function fetchUser(id) {
        const r = await fetch("/u/" + id);
        return r.json();
      }
    `;
    const out = transform(src, "/abs/path/to/users.ts");

    assertContains(
      out,
      "async function fetchUser(",
      '__tf_c?.enter("users", "fetchUser"',
      '__tf_c?.exit("users", "fetchUser"',
      'await fetch("/u/" + id)',
      "return r.json();",
      "try {",
      "finally {",
    );
    expect(out).toMatch(/\{\s*id\s*\}/);
  });

  it("wraps an async arrow expression assigned to a const", () => {
    const src = `
      const load = async (path) => {
        const r = await fetch(path);
        return r.text();
      };
    `;
    const out = transform(src, "/abs/path/to/loader.ts");

    assertContains(
      out,
      "async ",
      '__tf_c?.enter("loader", "load"',
      '__tf_c?.exit("loader", "load"',
      "await fetch(path)",
      "return r.text();",
    );
  });

  it("wraps an async class method using ClassName.method naming", () => {
    const src = `
      class Repo {
        async load(id) { return await fetch(id); }
      }
    `;
    const out = transform(src, "/abs/path/to/repo.ts");

    assertContains(
      out,
      "async load(",
      '__tf_c?.enter("repo", "Repo.load"',
      '__tf_c?.exit("repo", "Repo.load"',
      "return await fetch(id);",
    );
  });

  it("wraps an async ObjectMethod with owner.method naming", () => {
    const src = `
      const api = { async fetch(id) { return await xfr(id); } };
    `;
    const out = transform(src, "/abs/path/to/api.ts");

    assertContains(
      out,
      '__tf_c?.enter("api", "api.fetch"',
      '__tf_c?.exit("api", "api.fetch"',
      "return await xfr(id);",
    );
  });

  it("does NOT wrap sync generator functions (still deferred)", () => {
    const src = `
      function* gen() { yield 1; }
      class A { *iter() { yield 2; } }
      const g = function* () { yield 3; };
    `;
    const out = transform(src, "/abs/path/to/gens.ts");

    expect(out).toBe(baseline(src, "/abs/path/to/gens.ts"));
    assertNotContains(out, "__tf_c", "_getActiveClient");
  });

  it("does NOT wrap async generator functions (still deferred)", () => {
    const src = `
      async function* asyncGen() { yield await 1; }
    `;
    const out = transform(src, "/abs/path/to/agens.ts");

    expect(out).toBe(baseline(src, "/abs/path/to/agens.ts"));
    assertNotContains(out, "__tf_c", "_getActiveClient");
  });

  it("runtime smoke test: enter/exit/capture fire correctly across awaits", async () => {
    const src = `
      async function ok(x) { return x + 1; }
      async function bad() { throw new Error("boom"); }
      module.exports = { ok, bad };
    `;
    const out = transform(src, "/abs/path/to/smoke.ts");

    // Sanity: both got wrapped.
    assertContains(
      out,
      '__tf_c?.enter("smoke", "ok"',
      '__tf_c?.enter("smoke", "bad"',
    );

    const events: Array<[string, ...unknown[]]> = [];
    const mockClient = {
      enter: (...args: unknown[]) => {
        events.push(["enter", ...args]);
      },
      exit: (...args: unknown[]) => {
        events.push(["exit", ...args]);
      },
      // The babel-plugin's catch clause now calls `__tf_c?.caught(class,
      // method, err)` (runtime-js >= 0.2.0). Register the stub here so the
      // generated code finds it on the mock client.
      caught: (className: string, method: string, err: { message: string }) => {
        events.push(["caught", className, method, err.message]);
      },
    };
    const stripped = stripRuntimeImport(out);
    const sandbox: Record<string, unknown> = {
      module: { exports: {} as unknown },
      __tf_getClient: () => mockClient,
      // No standalone __tf_capture stub: the wrapper now uses the client's
      // own .caught() method, which short-circuits when the client is null.
    };
    vm.runInNewContext(stripped, sandbox);

    const exported = (
      sandbox.module as {
        exports: {
          ok: (x: number) => Promise<number>;
          bad: () => Promise<never>;
        };
      }
    ).exports;

    // ok(1) resolves to 2 with enter+exit recorded.
    const okResult = await exported.ok(1);
    expect(okResult).toBe(2);
    expect(events.length).toBe(2);
    expect(events[0]?.[0]).toBe("enter");
    expect(events[0]?.[2]).toBe("ok");
    expect(events[1]?.[0]).toBe("exit");
    expect(events[1]?.[2]).toBe("ok");

    // bad() rejects with "boom" and records enter, capture, exit in order.
    events.length = 0;
    let caught: Error | undefined;
    try {
      await exported.bad();
    } catch (e) {
      caught = e as Error;
    }
    expect(caught?.message).toBe("boom");
    expect(events.length).toBe(3);
    expect(events[0]?.[0]).toBe("enter");
    // The caught event is now attributed to the originating function
    // (smoke / bad) rather than the generic captureException helper.
    expect(events[1]).toEqual(["caught", "smoke", "bad", "boom"]);
    expect(events[2]?.[0]).toBe("exit");
  });

  it("respects @notrace on an async function declaration", () => {
    const src = `
      /* @notrace */ async function quiet() { return 1; }
    `;
    const out = transform(src, "/abs/path/to/quiet.ts");

    // The function stays unwrapped — no enter/exit emitted at all.
    assertNotContains(out, "__tf_c?.enter", "__tf_c?.exit");
    assertContains(out, "async function quiet(", "return 1;");
  });

  it("emits the params object including default-valued params on async fns", () => {
    const src = `
      async function f(a, b = 1) { return a + b; }
    `;
    const out = transform(src, "/abs/path/to/f.ts");

    assertContains(out, '__tf_c?.enter("f", "f"');
    expect(out).toMatch(/\{\s*a,\s*b\s*\}/);
  });

  it("omits the params object when traceArguments is false on an async fn", () => {
    const src = `
      async function f(a, b) { return a + b; }
    `;
    const out = transform(src, "/abs/path/to/f.ts", {
      traceArguments: false,
    });

    assertContains(out, '__tf_c?.enter("f", "f", undefined');
    assertNotContains(out, '__tf_c?.enter("f", "f", {');
  });

  it("is idempotent across two plugin instances over an async function", () => {
    const src = `
      async function fetchUser(id) {
        return await fetch(id);
      }
    `;
    const result = transformSync(src, {
      filename: "/abs/path/to/idem.ts",
      babelrc: false,
      configFile: false,
      plugins: [
        [plugin, {}, "traceflow-a"],
        [plugin, {}, "traceflow-b"],
      ],
    });
    if (!result || result.code == null) {
      throw new Error("transformSync returned no code");
    }
    const out = result.code;

    // Exactly one enter and one exit — second pass must see __tfWrapped.
    const enterCount = (out.match(/__tf_c\?\.enter\(/g) ?? []).length;
    const exitCount = (out.match(/__tf_c\?\.exit\(/g) ?? []).length;
    expect(enterCount).toBe(1);
    expect(exitCount).toBe(1);
  });
});
