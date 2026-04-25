# @umutcansu/traceflow-runtime

TraceFlow ingestion runtime for React Native and web JS. Posts
schema-v2 trace events to a TraceFlow server (the companion to the
Android runtime + IntelliJ/Android Studio viewer).

## Install

```bash
yarn add @umutcansu/traceflow-runtime
# or
npm install @umutcansu/traceflow-runtime
```

Optional: `pako` is loaded on demand when `CompressionStream` is
unavailable (older RN / Hermes). Add it yourself if you target those
environments:

```bash
yarn add pako
```

## Quick start

```ts
import { initTraceFlow, captureException, trace } from "@umutcansu/traceflow-runtime";

initTraceFlow({
  endpoint: "https://traceflow.example.com/traces",
  appId: "com.bufraz.parla",
  platform: "react-native",
  appVersion: "1.2.3",
  buildNumber: "45",
  userId: "user-123",
  token: process.env.TRACEFLOW_TOKEN,   // when the server has TRACEFLOW_INGEST_TOKEN set
});

// Later in your app:
try {
  riskyOperation();
} catch (e) {
  captureException(e);
}

// Or wrap a block to emit ENTER/EXIT around it:
const parsed = trace("parseFoo", () => JSON.parse(raw));
```

Global unhandled errors (both `ErrorUtils` on RN and
`error` / `unhandledrejection` on the web) are captured automatically.

## API

| Function | Purpose |
|---|---|
| `initTraceFlow(cfg)` | Start the runtime. Returns a `TraceFlowClient`. |
| `captureException(err, meta?)` | Emit a CATCH event. |
| `trace(name, fn)` | Sync ENTER/EXIT around `fn`. |
| `traceAsync(name, fn)` | Same, for `async` functions. |
| `setUserId(id \| null)` | Update the userId on every subsequent event. |
| `setEnabled(value)` | (since `0.2.2`) Runtime kill-switch — when `false`, every event-emitter on the active client becomes a no-op. The buffer and the flush loop stay alive, so re-enabling resumes immediately. |
| `isEnabled()` | (since `0.2.2`) Whether `setEnabled` is currently allowing events through. |
| `shutdown()` | Final flush; call on app background or test exit. |

`TraceFlowClient` also exposes:
- `enter(className, method, params?)` and `exit(className, method, result?, durationMs?)` for manual instrumentation where `trace()` doesn't fit
- `caught(className, method, err)` (since 0.2.0) emits a `CATCH` event tagged with the originating function rather than the generic `captureException.manual` slot. Used by `@umutcansu/traceflow-babel-plugin` so auto-instrumented catches keep their function labels.

## Auto-instrumentation

If you don't want to call `trace()` by hand, install
[`@umutcansu/traceflow-babel-plugin`](https://www.npmjs.com/package/@umutcansu/traceflow-babel-plugin)
and add it to `babel.config.js`. Every function declaration, arrow,
class method, and async variant in your source then ships ENTER /
EXIT / CATCH events without any further code change.

## Wire format

Every event is POSTed as part of a JSON array with the standard
schema-v2 fields:

```json
{
  "type": "CATCH",
  "ts": 1715200000123,
  "schemaVersion": 2,
  "platform": "react-native",
  "runtime": "hermes",
  "appId": "com.bufraz.parla",
  "appVersion": "1.2.3",
  "buildNumber": "45",
  "userId": "user-123",
  "deviceId": "a7f3...9c1",
  "sessionId": "2e8a...4b2",
  "exception": "TypeError",
  "message": "undefined is not a function",
  "stack": ["at render (App.tsx:42:10)", "at invokeCallback (react.js:1234)"]
}
```

Request bodies are gzip-compressed by default (`Content-Encoding: gzip`)
when the runtime has `CompressionStream` or `pako`. The server sends
gzip-compressed responses for readers above ~860 B.

## Configuration reference

| Field | Type | Default | Notes |
|---|---|---|---|
| `endpoint` | `string` | — | Required. Full URL to `POST /traces`. |
| `appId` | `string` | — | Required. Reverse-DNS identifier. |
| `platform` | `"react-native"` \| `"web-js"` | — | Required. |
| `token` | `string` | — | Matches server's `TRACEFLOW_INGEST_TOKEN`. |
| `appVersion`, `buildNumber` | `string` | — | From `app.json` / build config. |
| `userId` | `string` | — | Updateable via `setUserId()`. |
| `runtime` | `string` | auto | Best-effort detection. |
| `flushIntervalMs` | `number` | `5000` | How often to send queued batches. |
| `batchSize` | `number` | `50` | Max events per POST. |
| `maxBufferSize` | `number` | `1000` | Oldest events drop when exceeded. |
| `compress` | `boolean` | `true` (auto-off on RN) | Gzip request bodies. The runtime forces this off on React Native because OkHttp strips outgoing `Content-Encoding` headers; see "React Native + gzip" below. |
| `maskPatterns` | `RegExp[]` | PII defaults | Matched against `params` keys. |
| `retryOnError` | `boolean` | `true` | Prepend failed batches back into the buffer. |

## Security considerations

### Transport
- **Always deploy the server behind TLS** in production. The sample-server
  ships HTTP for local dev; reverse-proxy it (nginx/Caddy/Cloudflare) with
  HTTPS before pointing a mobile app at it.
- Gzip transport is on by default for web/Node. If you hit a proxy that
  mis-handles `Content-Encoding: gzip` on mobile networks, set
  `compress: false` explicitly.

### React Native + gzip
On React Native, the runtime **always sends raw JSON regardless of the
`compress` flag**. RN's fetch is implemented on top of OkHttp, whose
`BridgeInterceptor` strips outgoing `Content-Encoding` headers — the
server then sees gzip magic bytes with no header and the JSON parser
fails with HTTP 500. This was reported in production with
`runtime-js@0.2.0` on RN 0.81 + Expo SDK 54 + Hermes, and fixed in
`0.2.1`. Upgrade to `^0.2.1` if you hit `[TraceFlow] flush error: HTTP
500` from an RN device:
```bash
yarn add @umutcansu/traceflow-runtime@^0.2.1
```
The companion `sample-server` also has a magic-byte fallback that
decodes header-less gzip bodies, so older runtime clients keep working
against an upgraded server.

### Ingest token
- When the server has `TRACEFLOW_INGEST_TOKEN` set, supply the same value
  via the `token` config field. Tokens **cannot** stay fully secret on a
  distributed mobile app (they live in the APK / IPA), so treat them as a
  coarse filter, not an auth boundary. Rotate on compromise.
- For apps with strict compliance needs, proxy requests through your own
  backend and use short-lived per-user JWTs instead.

### PII masking
The runtime applies two layers of masking before every POST:

1. **Key-based (`maskPatterns`)** — applied to `params` object keys. If a
   key matches any of the patterns (defaults: `password`, `token`, `jwt`,
   `api_key`, `secret`, etc.) the value is replaced with `"***"`.
2. **Value-based (`maskMessagePatterns`)** — applied to exception messages,
   stack frames, and result strings. Defaults scrub obvious secret shapes:

   ```
   password=foo         → password=***
   Bearer eyJhbGci...   → Bearer ***
   eyJhbGci....sig      → ***            (bare JWT)
   ```

   Pass `maskMessagePatterns: []` to disable, or supply your own list
   (additive: you replace the default rather than extend it).

**What is NOT masked automatically**:
- Free-form strings you pass as `params` values — only keys are examined.
- Data buried inside complex objects (the runtime stringifies once).
- Stack frame *paths* — if your file names contain secrets, rename them.

When in doubt, mask at the source: don't put secrets in thrown errors,
use symbolic names in logs (`userEmail: "<masked>"`), and route real
credentials through a dedicated secret manager.

### Certificate pinning
Not implemented by default. If you target regulated environments,
substitute the runtime's internal `fetch` call with a pinned client
(e.g. `react-native-ssl-pinning`) — open an issue if you'd like a
config hook for this.

## Development

```bash
npm install
npm run typecheck
npm run build
# With a local sample-server running on :4567
npm run smoke
```

## License

MIT
