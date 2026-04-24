/**
 * Stage 4 acceptance tests for the TraceFlow Babel plugin.
 *
 * Stage 4 wraps `ClassMethod`, `ClassPrivateMethod`, and `ObjectMethod` nodes.
 * Method names are derived from the owning class/object plus the method's
 * key, with special prefixes for getters (`get_`), setters (`set_`), and the
 * special-cased `constructor`. Derived-class constructors are skipped because
 * any prelude before `super()` would be illegal.
 *
 * Out of scope (deferred to later stages):
 *  - async / generator methods (Stage 5)
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

/** Strip the runtime import line so we can run output under vm. */
function stripRuntimeImport(out: string): string {
  return out.replace(
    /^import[^\n]*from\s*"@umutcansu\/traceflow-runtime";\s*/m,
    "",
  );
}

describe("Stage 4: class-method / object-method wrapping", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  beforeEach(() => {
    process.env.NODE_ENV = "test";
  });
  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
  });

  it("wraps a plain class method using ClassName.method naming", () => {
    const src = `
      class Cart {
        add(item) { this.items.push(item); }
      }
    `;
    const out = transform(src, "/abs/path/to/cart.ts");

    assertContains(
      out,
      '__tf_c?.enter("cart", "Cart.add"',
      '__tf_c?.exit("cart", "Cart.add"',
      "try {",
      "this.items.push(item);",
      "finally {",
    );
    // Params object is emitted in shorthand form. Allow either single-line
    // or multi-line printer output.
    expect(out).toMatch(/\{\s*item\s*\}/);
  });

  it("wraps a constructor on a non-derived class", () => {
    const src = `
      class A {
        constructor() { this.x = 1; }
      }
    `;
    const out = transform(src, "/abs/path/to/a.ts");

    assertContains(
      out,
      '__tf_c?.enter("a", "A.constructor"',
      "this.x = 1;",
    );
  });

  it("does NOT wrap a derived-class constructor", () => {
    const src = `
      class A {}
      class B extends A {
        constructor() { super(); this.y = 2; }
      }
    `;
    const out = transform(src, "/abs/path/to/b.ts");

    assertNotContains(out, '"B.constructor"');
    // super() must still be present and untouched.
    assertContains(out, "super();", "this.y = 2;");
  });

  it("still wraps non-constructor methods on a derived class", () => {
    const src = `
      class A {}
      class B extends A {
        constructor() { super(); this.y = 2; }
        greet() { return "hi"; }
      }
    `;
    const out = transform(src, "/abs/path/to/b.ts");

    // Constructor skipped.
    assertNotContains(out, '"B.constructor"');
    // greet wrapped.
    assertContains(
      out,
      '__tf_c?.enter("b", "B.greet"',
      'return "hi";',
    );
  });

  it("uses get_/set_ prefixes for accessors and includes setter param", () => {
    const src = `
      class C {
        get total() { return 42; }
        set total(v) { this.t = v; }
      }
    `;
    const out = transform(src, "/abs/path/to/c.ts");

    assertContains(
      out,
      '__tf_c?.enter("c", "C.get_total"',
      '__tf_c?.enter("c", "C.set_total"',
      "return 42;",
      "this.t = v;",
    );
    expect(out).toMatch(/\{\s*v\s*\}/);
  });

  it("preserves the # prefix on a private method's name", () => {
    const src = `
      class D {
        #secret() { return 1; }
      }
    `;
    const out = transform(src, "/abs/path/to/d.ts");

    assertContains(out, '__tf_c?.enter("d", "D.#secret"', "return 1;");
  });

  it("wraps an ObjectMethod shorthand with owner.method naming from a VariableDeclarator", () => {
    const src = "const obj = { fetch() { return 42; } };";
    const out = transform(src, "/abs/path/to/o.ts");

    assertContains(
      out,
      '__tf_c?.enter("o", "obj.fetch"',
      "return 42;",
    );
  });

  it("falls back to <obj>.method for an object literal with no nameable owner", () => {
    // Bare expression statement; no VariableDeclarator/AssignmentExpression
    // around the ObjectExpression. Documents the `<obj>` fallback.
    const src = "({ run() { return 1; } });";
    const out = transform(src, "/abs/path/to/m.ts");

    assertContains(out, '__tf_c?.enter("m", "<obj>.run"');
  });

  it("derives the class name from a VariableDeclarator for an anonymous class", () => {
    const src = "const Helper = class { calculate() { return 5; } };";
    const out = transform(src, "/abs/path/to/h.ts");

    assertContains(out, '__tf_c?.enter("h", "Helper.calculate"');
  });

  it("uses the <AnonClass> fallback for a truly anonymous class expression", () => {
    const src = "(class { ping() { return 1; } });";
    const out = transform(src, "/abs/path/to/p.ts");

    assertContains(out, '__tf_c?.enter("p", "<AnonClass>.ping"');
  });

  it("does not visit static blocks but still wraps sibling methods", () => {
    const src = `
      class E {
        static {}
        foo() { return 1; }
      }
    `;
    const out = transform(src, "/abs/path/to/e.ts");

    assertContains(out, '__tf_c?.enter("e", "E.foo"');
    // The static block stays unwrapped (no enter call referencing it).
    expect(countOccurrences(out, "__tf_c?.enter(")).toBe(1);
  });

  it("does not visit class properties but still wraps sibling methods", () => {
    const src = `
      class F {
        x = 1;
        bar() { return 2; }
      }
    `;
    const out = transform(src, "/abs/path/to/f.ts");

    assertContains(out, '__tf_c?.enter("f", "F.bar"');
    expect(countOccurrences(out, "__tf_c?.enter(")).toBe(1);
    // The class property assignment must remain.
    assertContains(out, "x = 1;");
  });

  it("does NOT wrap async class methods (Stage 5)", () => {
    const src = `
      class G {
        async load() { return 1; }
      }
    `;
    const out = transform(src, "/abs/path/to/g.ts");

    expect(out).toBe(baseline(src, "/abs/path/to/g.ts"));
    assertNotContains(out, "__tf_c", "_getActiveClient");
  });

  it("is idempotent across two plugin instances over the same AST", () => {
    const src = `
      class Cart {
        add(item) { this.items.push(item); }
      }
    `;
    const result = transformSync(src, {
      filename: "/abs/path/to/cart.ts",
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

  it("respects @notrace on a class method", () => {
    const src = `
      class H {
        /* @notrace */ secret() { return 1; }
      }
    `;
    const out = transform(src, "/abs/path/to/h.ts");

    assertNotContains(out, "__tf_c", "_getActiveClient");
    expect(out).toBe(baseline(src, "/abs/path/to/h.ts"));
  });

  it("preserves `super` resolution inside a wrapped derived-class method", () => {
    // Prove the wrapper does not break super.foo() lookups. We transform a
    // tiny module, stub the runtime, run under vm, and check the override
    // appends "!" to the parent return value.
    const src = `
      class P {
        greet() { return "P"; }
      }
      class C extends P {
        greet() { return super.greet() + "!"; }
      }
      module.exports = new C().greet();
    `;
    const out = transform(src, "/abs/path/to/superfile.ts");

    // Sanity: both methods got wrapped.
    assertContains(
      out,
      '__tf_c?.enter("superfile", "P.greet"',
      '__tf_c?.enter("superfile", "C.greet"',
    );

    const stripped = stripRuntimeImport(out);
    const sandbox: Record<string, unknown> = {
      module: { exports: {} as unknown },
      __tf_getClient: () => null,
      __tf_capture: () => undefined,
    };
    vm.runInNewContext(stripped, sandbox);
    expect((sandbox.module as { exports: string }).exports).toBe("P!");
  });

  it("preserves `this` inside a wrapped class method", () => {
    const src = `
      class K {
        constructor() { this.v = 7; }
        get() { return this.v; }
      }
      module.exports = new K().get();
    `;
    const out = transform(src, "/abs/path/to/kfile.ts");

    assertContains(
      out,
      '__tf_c?.enter("kfile", "K.constructor"',
      '__tf_c?.enter("kfile", "K.get"',
    );

    const stripped = stripRuntimeImport(out);
    const sandbox: Record<string, unknown> = {
      module: { exports: {} as unknown },
      __tf_getClient: () => null,
      __tf_capture: () => undefined,
    };
    vm.runInNewContext(stripped, sandbox);
    expect((sandbox.module as { exports: number }).exports).toBe(7);
  });

  it("omits the params object when traceArguments is false on a class method", () => {
    const src = `
      class L {
        add(item) { this.items.push(item); }
      }
    `;
    const out = transform(src, "/abs/path/to/l.ts", {
      traceArguments: false,
    });

    assertContains(out, '__tf_c?.enter("l", "L.add", undefined');
    assertNotContains(out, '__tf_c?.enter("l", "L.add", {');
  });
});
