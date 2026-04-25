# Changelog

All notable changes to TraceFlow are documented in this file. The
project follows [Semantic Versioning](https://semver.org/). Each
component (`runtime`, `gradle-plugin`, `studio-plugin`,
`runtime-js`, `babel-plugin`) is versioned and released independently.

## `runtime-js` [0.2.1] — 2026-04-25

Bug fix for React Native deployments. On RN/Hermes (verified on
React Native 0.81 + Expo SDK 54 + Hermes), every POST from
`@umutcansu/traceflow-runtime@0.2.0` was returning HTTP 500 because:

- The runtime gzipped the body and set `Content-Encoding: gzip`.
- React Native's fetch is implemented on top of OkHttp, whose
  `BridgeInterceptor` strips outgoing `Content-Encoding` headers (it
  manages transparent compression on its own).
- The server gated decompression on the header alone; with the header
  gone, the JSON parser saw raw gzip magic bytes (`1f 8b ...`) and
  threw `JsonDecodingException`.

### Fix

- **Runtime (`sender.ts`)**: detect React Native at construction and
  skip gzip on that platform regardless of the user's `compress` flag.
  Web/Node behaviour is unchanged — both still gzip and set the
  `Content-Encoding` header. Verified by simulating RN
  (`navigator.product === "ReactNative"`) and confirming the POST
  body arrives as plain JSON without the header.
- **Sample-server (`Main.kt`)**: defence-in-depth magic-byte fallback —
  if the first two bytes of the body are `0x1F 0x8B` (gzip magic),
  decompress regardless of the `Content-Encoding` header. This keeps
  older runtime clients (and any future platform with the same OkHttp
  quirk) working without manual upgrades.

End-to-end verified against the sample-server with three scenarios:
gzip body without the header (legacy RN behaviour), correct
gzip + header (web/Node), and plain JSON (post-fix RN). All three
return HTTP 200 and round-trip into SQLite.

## `babel-plugin` [0.1.2] — 2026-04-25

Skip Metro / Rollup virtual polyfill files. The 0.1.1 release fixed
the Metro modules-commonjs orphan-import case, but a separate boot
crash showed up on a real Mi 9T + Hermes run:

```
ReferenceError: Property 'require' doesn't exist
Non-js exception: AppRegistryBinding::startSurface failed.
                  Global was not installed.
```

Cause: Metro represents its bootstrap polyfills (e.g.
`\0polyfill:external-require`, `\0polyfill:assets-registry`) as
virtual modules. Their filename gets joined with the project root
and arrives at Babel as e.g.
`/abs/path/to/project/\0polyfill:external-require`. The 0.1.1 skip
heuristic only checked `filename.startsWith("\0")`, missed the
embedded NUL byte, and instrumented the polyfill — which runs
*before* the module system bootstraps, so a `require(...)` call
injected there had nothing to resolve and Hermes hard-crashed at
boot.

### Fix

`shouldSkipFile` now skips any filename containing a NUL byte
(`filename.includes("\0")`). NUL is illegal in real filesystem paths,
so its presence anywhere is a reliable virtual-file signal.

Verified end-to-end on a Mi 9T running the new
`examples/rn-app/` Expo demo: app boots, runtime initialises,
14 events post to a local sample-server with `platform=react-native`
tagged correctly. No regression in the Node test suite (71/71).

## `babel-plugin` [0.1.1] — 2026-04-25

Bug fix for Metro / React Native bundles. `0.1.0` injected the runtime
import via raw `t.importDeclaration`, which works under pure ESM but
left an orphan `import` in the bundle when Metro's modules-commonjs
pass had already converted other imports to `require()`. Hermes
rejected the bundle with:

```
SyntaxError: import declaration must be at top level of module
```

Reported by a user shipping `@umutcansu/traceflow-runtime@0.2.0` on
RN 0.81 + Expo SDK 54 + Hermes; the workaround was a `TF_DISABLE=1`
build flag that disabled the plugin entirely.

### Fix

Switched `helpers/imports.ts` to use `addNamed` from
`@babel/helper-module-imports`. That helper is the canonical Babel
API for adding imports during a plugin pass: it picks the right
syntax for the active module system (ESM, CJS interop, AMD/UMD as
configured by the consuming preset), deduplicates within a file, and
returns a `t.Identifier` whose binding is visible to subsequent
transforms.

The local alias name changed from `__tf_getClient` to `_tf_getClient`
(single underscore — the prefix `addNamed` chooses by default for the
hint we pass). The body builder threads the returned Identifier through
its `WrapInputs.clientFn` field and clones it into call sites, so any
future name uniquification is handled correctly.

Verified end-to-end:
- 71/71 unit tests green.
- `e2e.smoke.mjs` round-trips 9 events with correct CATCH attribution.
- Direct CJS-mode transform (mimicking Metro's pipeline) emits a
  `require("@umutcansu/traceflow-runtime")` call instead of the
  orphan `import`, which Hermes accepts cleanly.

Adds `@babel/helper-module-imports` as a dependency. No API change
for consumers — same plugin entry, same options.

## `babel-plugin` [0.1.0] — 2026-04-25

First publishable release of `@umutcansu/traceflow-babel-plugin` —
zero-code auto-instrumentation for JavaScript / TypeScript via Babel.
Wraps every function declaration, arrow / function expression, class
method (including getters, setters, private), object method, and
async variant of all of the above with `ENTER` / `EXIT` / `CATCH`
calls into the runtime. Body shape uses plain `try`/`finally` with
the original body verbatim, so `super`, `arguments`, `this`, and
`return` semantics are preserved without any source-level surgery.
Generators are deferred. Requires `@umutcansu/traceflow-runtime
>= 0.2.0` for the `caught()` API.

## `runtime-js` [0.2.0] — 2026-04-25

Adds `TraceFlowClient.caught(className, method, err)` so the
companion Babel plugin can emit `CATCH` events tagged with the
originating function instead of the generic `captureException.manual`
slot. Existing `captureException()` semantics are unchanged so the
0.1.0 global error hooks keep attributing the same way.

## [2.0.0] — 2026-04-25

Multi-platform ingestion: React Native and web JavaScript clients can
now push trace events into the same TraceFlow backend that Android
uses, and the Android Studio / IntelliJ plugin renders the combined
stream with per-app and per-platform filters.

### New components

- **`@umutcansu/traceflow-runtime`** npm package — schema-v2 trace
  ingestion for React Native and web JS. Manual `trace()` /
  `captureException()` plus automatic global error hooks
  (`ErrorUtils` on RN, `window.error` and `unhandledrejection` on
  the web). Gzip transport via `CompressionStream` with an optional
  `pako` peer fallback. Persistent anonymous `deviceId`, per-run
  `sessionId`, and PII-safe masking of stack frames / exception
  messages.
- **`sample-server` opt-in auth** — `TRACEFLOW_INGEST_TOKEN` gates
  `POST /traces` behind `X-TraceFlow-Token`. `TRACEFLOW_JWT_SECRET`
  gates admin endpoints (`GET /traces`, `/apps`, `/stats`,
  `DELETE /traces`) behind a Bearer JWT. Both are opt-in; the
  sample-server still starts fully open when the env vars are unset.
- **Rate limiting** — IP + token buckets on `POST /traces` backed by
  the Ktor rate-limit plugin (600 req/min per IP, 10 000 per token).
  Calibrate once you have real device × event/sec data.
- **GDPR right-to-erasure** — `DELETE /traces?userId=<id>` removes
  every event tied to that user, admin-auth when enabled.
- **Zip-bomb guard** — request bodies are capped at 5 MB compressed
  and 50 MB after gunzip. Oversize requests return HTTP 413 before
  they can exhaust memory.

### Added

- Schema v2: new optional event fields — `schemaVersion`, `platform`,
  `runtime`, `appId`, `appVersion`, `buildNumber`, `userId`,
  `deviceId`, `sessionId`, `stack`, `sourceMapId`, `proguardMapId`,
  `isMinified`. All nullable so v1 clients keep working unchanged.
- `TraceLog.startRemote(context, endpoint, ...)` additive overload
  on Android: emits schema-v2 fields on every event when the host
  app opts in. The legacy no-`Context` overload is **unchanged** and
  still produces v1-shape events, so existing integrations are not
  broken.
- `GET /traces` pagination: responses are now
  `{ events: [...], nextCursor: <ts or null> }`. New query params
  `until`, `platform`, `appId`, `userId`, `deviceId`, `level`, `tag`,
  `limit` (default 500, max 5000).
- `GET /apps` — per-app summary endpoint (`appId`, `eventCount`,
  `lastSeen`, `platforms[]`). Powers the plugin's app picker.
- SQLite indexes on `ts`, `(app_id, ts)`, `(platform, ts)`,
  `(user_id, ts)` so the new filters stay fast.
- **Studio plugin**:
  - Parses schema v2 fields (platform, appId, userId, sessionId, …)
    and joins structured stack arrays into the detail column.
  - New Remote-tab filters: app picker (populated from `GET /apps`),
    platform combo, userId text field. Selections flow through as
    server-side query params.
  - New `Platform` and `App` columns (hidden by default, same
    pattern as Manufacturer/Device/Tag).
  - Grace-parses both the new envelope and the legacy raw-array
    response shape, so a 2.0 plugin still works against a 1.x
    server during rollout.
- **Gzip transport** across the board: `RemoteSender` gzips request
  bodies by default (`compress = true`); server decompresses on
  ingest and optionally gzips responses via the Ktor `Compression`
  plugin.

### Changed

- `GET /traces` response shape is now the envelope
  `{ events, nextCursor }`. Old clients that expected a bare JSON
  array must migrate — the studio-plugin 2.0 handles both, but
  third-party consumers should update. See
  [docs/migrate-v1-to-v2.md](docs/migrate-v1-to-v2.md).
- `sample-server` adds Ktor compression, rate-limit, auth, and
  auth-jwt modules. Out-of-the-box behaviour is preserved when the
  new env vars are unset.
- Plugin's `EventTableModel` grew two columns (Platform, App). The
  column index order in the model moved; downstream consumers that
  read by numeric index should update.

### Deprecated

- None in this release. The legacy `TraceLog.startRemote(endpoint, …)`
  overload stays first-class and continues to emit v1-shape events.
  It may be marked `@Deprecated` in a future minor once the v2
  overload is the common path.

### Security

- Stack frames, exception messages, and result strings are scrubbed
  through `DEFAULT_MESSAGE_MASK_PATTERNS` in runtime-js before being
  sent. Default patterns collapse `password=`, `token=`, `Bearer …`,
  and bare JWTs into `***`.
- Constant-time token comparison (`MessageDigest.isEqual`) on the
  server so ingest tokens cannot leak one byte at a time via
  response-timing oracles.

### Notes

- Implementation plan: [docs/v2-implementation-plan.md](docs/v2-implementation-plan.md).
- Architecture / multi-platform design: [docs/traceflow-multi-platform-plan.md](docs/traceflow-multi-platform-plan.md).
- Long-term product direction: [docs/product-vision.md](docs/product-vision.md).

## [1.0.x]

See [git history](https://github.com/umutcansu/TraceFlow/commits/main)
for the 1.x line.
