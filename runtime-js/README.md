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
| `shutdown()` | Final flush; call on app background or test exit. |

`TraceFlowClient` also exposes `enter` / `exit` for manual
instrumentation where `trace()` doesn't fit.

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
| `compress` | `boolean` | `true` | Gzip request bodies. |
| `maskPatterns` | `RegExp[]` | PII defaults | Matched against `params` keys. |
| `retryOnError` | `boolean` | `true` | Prepend failed batches back into the buffer. |

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
