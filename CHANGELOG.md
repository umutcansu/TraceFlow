# Changelog

All notable changes to TraceFlow are documented in this file. The
project follows [Semantic Versioning](https://semver.org/). Each
component (`runtime`, `gradle-plugin`, `studio-plugin`,
`runtime-js`) is versioned and released independently.

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
