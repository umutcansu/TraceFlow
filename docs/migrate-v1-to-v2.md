# Migrating from TraceFlow 1.x to 2.0

TraceFlow 2.0 is **backwards-compatible for Android integrations** —
existing apps on 1.x keep working without code changes when you bump
the dependency. The breaking changes affect direct HTTP consumers of
the server API and anyone who was relying on schema-level guarantees
in custom tooling.

This guide walks through each component and tells you exactly what
to do.

## TL;DR

| You are… | Action |
|---|---|
| Android app using `runtime` + `gradle-plugin` | Bump versions to `2.0.0`. Nothing else is required. |
| Android app that wants richer events (appId, userId, sessionId) | Bump + migrate `TraceLog.startRemote` to the new overload. |
| IntelliJ plugin user | Update to `2.0.0` from Marketplace. It still reads 1.x servers. |
| React Native or web app | New — install `@umutcansu/traceflow-runtime` (no 1.x existed). |
| Running the sample-server in production | Set `TRACEFLOW_INGEST_TOKEN` and `TRACEFLOW_JWT_SECRET` env vars, otherwise you continue to accept anonymous traffic. |
| Custom HTTP consumer of `GET /traces` | Response shape changed — update parser. |

## 1. Runtime (`io.github.umutcansu:traceflow-runtime`)

Bump the dependency:

```kotlin
// build.gradle.kts
dependencies {
  implementation("io.github.umutcansu:traceflow-runtime:2.0.0")
}
```

That's it. Your existing calls to
`TraceLog.startRemote(endpoint, …)` produce identical v1-shape
events. No behaviour changed.

### Opt in to schema v2 (optional, recommended)

Call the new overload from `Application.onCreate` to include
`schemaVersion=2`, `platform`, `deviceId`, `sessionId`, and your
app metadata in every emitted event:

```kotlin
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    TraceLog.startRemote(
      context     = this,                       // new required first parameter
      endpoint    = "https://traceflow.example.com/traces",
      appId       = BuildConfig.APPLICATION_ID,
      appVersion  = BuildConfig.VERSION_NAME,
      buildNumber = BuildConfig.VERSION_CODE.toString(),
    )
  }
}
```

`deviceId` is generated once and persisted in
`SharedPreferences("traceflow_prefs")`. Clear app data to reset it.

Runtime `userId` updates after login / logout:

```kotlin
TraceLog.setUserId("user-${session.userId}")
// or on logout:
TraceLog.setUserId(null)
```

## 2. Gradle plugin (`io.github.umutcansu.traceflow`)

```kotlin
// settings.gradle.kts or project-level build.gradle.kts
plugins {
  id("io.github.umutcansu.traceflow") version "2.0.0"
}
```

DSL config is unchanged — all `traceflow { }` options from 1.x still
work the same way.

## 3. Android Studio / IntelliJ plugin

Update from the Marketplace (`Settings → Plugins → Updates`) or
download 2.0.0 from
<https://plugins.jetbrains.com/plugin/30959-traceflow>.

What's new in the UI:

- **Platform** and **App** columns (hidden by default; enable via the
  Columns popup).
- **Remote tab → second row** — App combo, Platform combo, User
  filter. Populated from `GET /apps` on connect.
- **Grace-parse**: 2.0 plugin works against both a 1.x (raw-array
  response) and a 2.0 (envelope) server, so you can upgrade plugin
  and server independently.

Nothing to migrate if you only consumed the UI.

## 4. React Native / web JavaScript (new)

No 1.x existed, so this is greenfield. Install the new runtime:

```bash
yarn add @umutcansu/traceflow-runtime
```

Minimal integration:

```ts
// App.tsx or entry
import { initTraceFlow, captureException } from '@umutcansu/traceflow-runtime';

initTraceFlow({
  endpoint: 'https://traceflow.example.com/traces',
  appId: 'com.example.myapp',
  platform: 'react-native',          // or 'web-js'
  appVersion: '1.0.0',
  userId: currentUserId,             // optional, updatable via setUserId
  token: process.env.TRACEFLOW_TOKEN, // only when the server enforces it
});
```

Full API: see [runtime-js/README.md](../runtime-js/README.md).

## 5. Sample-server (self-hosted TraceFlow server)

If you're running the reference server, bump the image / jar to 2.0
and nothing changes in the default configuration — it keeps accepting
anonymous traffic.

### Turning on auth for production deployments (strongly recommended)

Set these environment variables:

```bash
# Require X-TraceFlow-Token on POST /traces
export TRACEFLOW_INGEST_TOKEN="$(openssl rand -hex 32)"

# Require Bearer JWT (HMAC256) on admin endpoints
export TRACEFLOW_JWT_SECRET="$(openssl rand -hex 64)"
export TRACEFLOW_JWT_ISSUER="traceflow"              # defaults to "traceflow"
export TRACEFLOW_JWT_AUDIENCE="traceflow-admin"      # defaults to "traceflow-admin"
```

Then configure the token on every client:

```kotlin
// Android runtime
TraceLog.startRemote(
  this,
  endpoint = "https://traceflow.example.com/traces",
  headers = mapOf("X-TraceFlow-Token" to BuildConfig.TRACEFLOW_TOKEN),
  appId = BuildConfig.APPLICATION_ID,
)
```

```ts
// runtime-js
initTraceFlow({
  endpoint: '...',
  appId: '...',
  platform: 'react-native',
  token: process.env.TRACEFLOW_TOKEN,
});
```

And on the IntelliJ plugin: paste the admin JWT into the
**Authorization** field on the Remote tab as `Bearer <jwt>`.

### Database migration

The schema adds new nullable columns; the server calls
`SchemaUtils.createMissingTablesAndColumns` on boot so existing
SQLite databases migrate automatically with no data loss. The
migration is transactional.

### Response shape change

`GET /traces` now returns:

```json
{
  "events": [ { ... }, { ... } ],
  "nextCursor": 1745578904123
}
```

instead of the bare `[…]` array. If you have a third-party HTTP
consumer, update the parser. Pagination walks `?since=<nextCursor>`
until `nextCursor` is `null` (or omitted).

Filter query params are additive — old calls without any filter keep
working and just return the first page.

## 6. GDPR / right-to-erasure

New admin-auth endpoint:

```bash
curl -X DELETE \
  -H "Authorization: Bearer $ADMIN_JWT" \
  "https://traceflow.example.com/traces?userId=user-1234"
```

Returns the number of rows deleted. The call is idempotent: deleting
an unknown user returns `deleted 0 events`.

If `TRACEFLOW_JWT_SECRET` is unset the endpoint is unauthenticated;
don't expose the server publicly in that configuration.

## 7. Breaking compatibility changes at a glance

| API | 1.x | 2.0 | Migration |
|---|---|---|---|
| `GET /traces` response | raw array `[…]` | `{events, nextCursor}` | Update parser. Plugin grace-parses both. |
| `TraceLog.startRemote(endpoint, …)` | same | **unchanged** — v1 semantics preserved | No migration required. |
| `TraceLog.startRemote(context, endpoint, …)` | N/A | **new additive overload** — emits v2 fields | Opt in when you want richer events. |
| `sample-server` ingest | always accepts | token-gated when env var set | Set `TRACEFLOW_INGEST_TOKEN` in prod. |
| `DELETE /traces` | clears all events | clears all events, or only `?userId=X` | Optional — old call still works. |

## 8. Rolling back

If you discover a blocker:

- **Runtime** — pin back to `2.0.0` only affects new features; drop to
  `1.0.10` to remove the gzip encoder.
- **Plugin** — uninstall and install 1.0.17 from Marketplace. It
  parses the 1.x response format only; use against a 1.x server.
- **sample-server** — the only rollback-unsafe change is the new
  nullable columns. SQLite tolerates extra columns; you can run the
  1.x jar against a database that has 2.0 columns — the extra data
  is simply ignored.

## 9. Getting help

- Implementation details: [v2-implementation-plan.md](./v2-implementation-plan.md)
- Multi-platform architecture: [traceflow-multi-platform-plan.md](./traceflow-multi-platform-plan.md)
- Product direction: [product-vision.md](./product-vision.md)
- Issues: <https://github.com/umutcansu/TraceFlow/issues>
