# @umutcansu/traceflow-babel-plugin

Babel plugin that auto-instruments your JavaScript / TypeScript
functions with TraceFlow `ENTER` / `EXIT` / `CATCH` calls — no manual
log statements needed. Companion to
[@umutcansu/traceflow-runtime](https://www.npmjs.com/package/@umutcansu/traceflow-runtime).

```js
// Your code:
function add(a, b) {
  return a + b;
}

// What runs after Babel:
import { _getActiveClient as __tf_getClient,
         captureException as __tf_capture
       } from "@umutcansu/traceflow-runtime";

function add(a, b) {
  const __tf_c = __tf_getClient();
  const __tf_t0 = Date.now();
  __tf_c?.enter("math", "add", { a, b });
  try {
    return a + b;
  } catch (__tf_e) {
    __tf_capture(__tf_e);
    throw __tf_e;
  } finally {
    __tf_c?.exit("math", "add", undefined, Date.now() - __tf_t0);
  }
}
```

The original body is preserved verbatim inside the `try` block;
`super`, `arguments`, `this`, and `return` semantics are not touched.

## Install

```bash
yarn add -D @umutcansu/traceflow-babel-plugin
yarn add @umutcansu/traceflow-runtime           # peer
```

## Configure

Add to your `babel.config.js`:

```js
module.exports = {
  plugins: [
    '@umutcansu/traceflow-babel-plugin',
  ],
};
```

For React Native / Metro projects the same plugins block applies —
Metro picks up `babel.config.js` automatically.

Then initialise the runtime once at app startup:

```ts
import { initTraceFlow } from '@umutcansu/traceflow-runtime';

initTraceFlow({
  endpoint: 'https://traceflow.example.com/traces',
  appId: 'com.example.myapp',
  platform: 'react-native',          // or 'web-js'
  appVersion: '1.0.0',
  token: process.env.TRACEFLOW_TOKEN,
});
```

That's the entire wiring. Functions you write afterwards stream
ENTER/EXIT/CATCH events to the configured TraceFlow server with no
further code changes.

## What gets wrapped

- `function name(...) { ... }` — top-level function declarations
- `const fn = (...) => ...` and `const fn = function(...) { ... }` —
  arrow + function expressions assigned to a const, property, or
  member expression
- `class C { method(...) { ... } }` — including getters, setters,
  private (`#name`) methods, and constructors of non-derived classes
- `const obj = { method(...) { ... } }` — object literal shorthand
  methods
- `async` variants of all of the above

## What does NOT get wrapped

- **Anonymous callbacks** (`arr.map(x => x*2)`). Override with
  `instrumentAnonymous: true` if you really want them — names emit as
  `<anonymous>:<line>`.
- **Generator functions** (`function*`, async generators). Wrapping
  these would break lazy `yield` semantics; deferred.
- **Derived-class constructors** (`class B extends A { constructor()
  { super(); ... } }`). `super()` must be the first statement, and
  `enter()` cannot run before it. Other methods of derived classes
  wrap normally.
- **Functions with a `@notrace` leading comment**:
  `/* @notrace */ function quiet() {}`.
- **`node_modules`** — third-party code is never instrumented.
- **The TraceFlow runtime itself** — cycle protection.

## Options

```js
plugins: [
  ['@umutcansu/traceflow-babel-plugin', {
    enabled: true,
    runtimeImport: '@umutcansu/traceflow-runtime',
    excludePatterns: [/\.generated\.[jt]sx?$/],
    traceArguments: true,
    instrumentAnonymous: false,
  }],
],
```

| Option | Default | Effect |
|---|---|---|
| `enabled` | `true` unless `process.env.NODE_ENV === 'production'` | Master kill-switch. When `false` the plugin is a pure no-op. |
| `runtimeImport` | `'@umutcansu/traceflow-runtime'` | Module specifier to import runtime helpers from. Override during local development if you point at a tarball or workspace path. |
| `excludePatterns` | `[]` | Array of `RegExp` matched against the absolute filename. Matched files are skipped. Combined with the built-in `node_modules` and runtime-self skips. |
| `traceArguments` | `true` | When `false`, the ENTER call carries `undefined` instead of `{ a, b, ... }`. |
| `instrumentAnonymous` | `false` | When `true`, arrow/function expressions with no inferable name are wrapped using `<anonymous>:<line>` as the method label. |

## Compatibility notes

- **TypeScript**: works fine in projects that use `@babel/preset-typescript`.
  Register the plugin in the same `plugins:` block; Babel applies plugins
  before presets so the visitor sees the original (still-typed) source.
- **Source maps**: preserved. The plugin only modifies function bodies —
  surrounding declarations, types, and comments stay where they were.
- **Idempotency**: running the plugin twice over the same AST does not
  double-wrap. A `__tfWrapped` flag is attached to wrapped nodes during
  the pass.
- **`async`**: supported as of `0.1.0` — wraps with the same try/finally
  shape as sync functions. Try/finally semantics are identical for async,
  so `enter` runs before the first `await`, `finally` runs after the
  returned promise settles, and rejections flow through `catch` then
  re-throw exactly as before.
- **Performance**: every wrapped call adds one `_getActiveClient()` call,
  one optional-chained `enter`, one optional-chained `exit`, and a
  try/finally. When the runtime hasn't been initialised, all of those
  short-circuit — the cost is two function calls and one timer read.
  For tight hot loops, exclude the offending file with `excludePatterns`.

## EXIT result is intentionally `undefined`

The plugin does **not** capture each function's return value into the
`EXIT` event's `result` slot. Doing so would require rewriting the
function body (IIFE wrap or per-`return` rewriting), which breaks
`super`, `arguments`, and changes call-stack frames. Keep it simple:
the `EXIT` event tells you the function exited and how long it took;
if you need the return value for a specific call site, use the manual
`trace()` helper from `@umutcansu/traceflow-runtime` for that one
function.

## Troubleshooting

**My traces don't appear.**
Confirm `initTraceFlow()` is called before the wrapped code runs. The
plugin emits `__tf_c?.enter(...)` — if the runtime hasn't been
initialised, `_getActiveClient()` returns `null` and the chain
short-circuits. Add `console.log` next to `initTraceFlow()` to verify
it ran.

**Hot reload picks up old cached transforms.**
Metro caches Babel output. After first installing the plugin run:
```bash
yarn start --reset-cache
```

**A specific file is too noisy.**
Add a regex to `excludePatterns` (or sprinkle `/* @notrace */`
on individual functions).

**Production builds are too heavy.**
Set `NODE_ENV=production` and the plugin disables itself. Or pass
`enabled: false` explicitly.

**Metro / Hermes: `SyntaxError: import declaration must be at top
level of module`.**
Fixed in `0.1.1`. Earlier `0.1.0` injected the runtime import via raw
AST, which left an orphan `import` in the bundle when Metro's
modules-commonjs pass had already converted other imports to
`require()`. Hermes rejects bundles containing such orphans. Upgrade
to `0.1.1+`:
```bash
yarn add -D @umutcansu/traceflow-babel-plugin@^0.1.1
```
The plugin now uses `@babel/helper-module-imports#addNamed`, which
adapts the syntax to whatever module system the consuming preset
ends up emitting.

**Hermes: `ReferenceError: Property 'require' doesn't exist`** at app
boot.
Fixed in `0.1.2`. Metro injects virtual polyfill modules whose
filename contains an embedded NUL byte (e.g.
`/abs/project/\0polyfill:external-require`). `0.1.1` only skipped
filenames *starting* with `\0`, so these polyfills were instrumented
and the wrapper's `require()` call ran before Metro's `require`
shim was registered. Upgrade to `0.1.2+`:
```bash
yarn add -D @umutcansu/traceflow-babel-plugin@^0.1.2
```
The plugin now skips any filename containing `\0` anywhere, which
covers all known Metro virtual-module conventions.

## Versioning

Independent semver from the JVM artefacts. `0.x` while the API is
stabilising; first stable release will be `1.0.0`.

## License

MIT
