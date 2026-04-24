# TraceFlow Multi-Platform v2 — Implementation Plan (Steps 2–7)

Branch: `feat/multi-platform-v2`.
Reference roadmap: [traceflow-multi-platform-plan.md](traceflow-multi-platform-plan.md).

Completed so far:
- Gzip transport (commit `3b82662`, on `main`)
- Server-side schema v2 columns (commit `3c8cd37`, on `feat/multi-platform-v2`)

---

## 0. Dependency graph & merge cadence

### DAG

```
Step 2 (GET /traces filter + pagination) ──┐
                                            ├──► Step 5 (Plugin v2 parse + app picker)
Step 3 (GET /apps) ─────────────────────────┘

Step 4 (Android runtime v2) — independent; additive overload (non-breaking)
Step 6 (Auth + rate-limit + GDPR) — opt-in via env vars; landable any time
Step 7 (JS/RN runtime)   — independent; integrates with Step 6 if auth on
```

Hard blockers:
- Step 5 **requires** Step 2 — plugin's `gson.fromJson(body, JsonArray)` at [RemoteLogPoller.kt:90](studio-plugin/src/main/kotlin/io/github/umutcansu/traceflow/studio/remote/RemoteLogPoller.kt) breaks the moment server wraps response in `{events, nextCursor}`.

### Recommended PR cadence (small, atomic, reversible)

| PR | Steps | Notes |
|---|---|---|
| A | 2 + partial 5 (parser + response-shape grace) | Atomic because response shape changes; plugin grace-parses both old array and new object to avoid bricking legacy setups. |
| B | 3 + rest of 5 (app picker UI, platform badges, filters) | Depends on A. |
| C | 4 | Android runtime additive overload. Independent. |
| D | 6 | Opt-in auth + rate-limit + GDPR delete. |
| E | 7 | `runtime-js/` package (phase 1 manual API + global handlers). |

Release merge to `main`: after all land on `feat/multi-platform-v2`, one release PR bumps `runtime → 2.0.0`, `studio-plugin → 2.0.0`, server minor.

---

## Step 2 — GET /traces filter + pagination

### File
- [sample-server/.../Main.kt](sample-server/src/main/kotlin/io/github/umutcansu/traceflow/server/Main.kt): `queryEvents` (~L169-208), GET handler (~L298-302), `initDatabase` (~L95).

### Shape

```kotlin
@Serializable
data class TracesPage(val events: List<TraceEvent>, val nextCursor: Long?)

data class TraceQuery(
    val since: Long = 0L, val until: Long? = null,
    val platform: String? = null, val appId: String? = null,
    val userId: String? = null, val deviceId: String? = null,
    val level: String? = null, val tag: String? = null,
    val limit: Int = 500,
)
```

Query (chained `andWhere`, Exposed 0.57):
```kotlin
val rows = TraceEvents.selectAll()
    .andWhere { TraceEvents.ts greater q.since }
    .also { q.until?.let { u -> it.andWhere { TraceEvents.ts lessEq u } } }
    .also { q.platform?.let { p -> it.andWhere { TraceEvents.platform eq p } } }
    // ...appId, userId, deviceId, level→type, tag similarly
    .orderBy(TraceEvents.ts)
    .limit(q.limit.coerceIn(1, 5000) + 1)  // +1 probe
    .map(::rowToEvent)

val hasMore = rows.size > limit
TracesPage(
    events = if (hasMore) rows.take(limit) else rows,
    nextCursor = if (hasMore) rows[limit - 1].ts else null,
)
```

Indexes (inside `initDatabase`, via `exec`):
- `idx_events_ts`, `idx_events_app_ts`, `idx_events_platform_ts`, `idx_events_user_ts`.

### Risk
- **Breaking response shape**: raw `[...]` → `{events, nextCursor}`. Only reader is our plugin → update in same PR + add grace-parse for v1 servers (see Step 5).

### Acceptance
- `GET /traces?since=0&limit=2` → `{"events":[…2 items],"nextCursor":<ts>}` if more; else `nextCursor:null`.
- `platform=react-native&level=CATCH` filters correctly.
- `.schema trace_events` shows new indexes.

### PR title
`feat(server): paginate GET /traces and add platform/appId/userId/level filters`

---

## Step 3 — GET /apps

### File
- [Main.kt](sample-server/src/main/kotlin/io/github/umutcansu/traceflow/server/Main.kt)

### Shape

```kotlin
@Serializable
data class AppSummary(val appId: String, val eventCount: Int, val lastSeen: Long, val platforms: List<String>)

fun listApps(): List<AppSummary> = transaction {
    // 1 query: count + max(ts) grouped by appId
    // 1 query: distinct (appId, platform)
    // Combine; sort by lastSeen desc
}

get("/apps") { call.respond(listApps()) }
```

v1 rows with `null appId` excluded (intentional — picker surfaces opted-in apps only).

### Acceptance
- Empty DB → `[]`.
- One entry per distinct `appId`, correct `eventCount`/`lastSeen`, deduped `platforms`.

### PR title
`feat(server): add GET /apps summary endpoint for plugin app picker`

---

## Step 4 — Android runtime v2 (additive, non-breaking)

### File
- [TraceLog.kt](runtime/src/main/java/io/github/umutcansu/traceflow/TraceLog.kt): `startRemote` (L79-93), `emitJson` (L202-238).

### Constraint
Existing `startRemote(endpoint, ...)` must keep working **unchanged** (user mandate: "bozulmayacak").

### Shape — new overload, not replacement

```kotlin
@JvmStatic @JvmOverloads
fun startRemote(
    context: Context,
    endpoint: String,
    tag: String = "",
    headers: Map<String, String> = emptyMap(),
    batchSize: Int = 10,
    flushIntervalMs: Long = 3000L,
    allowInsecure: Boolean = false,
    compress: Boolean = true,
    appId: String? = null,
    appVersion: String? = null,
    buildNumber: String? = null,
    userId: String? = null,
) {
    startRemote(endpoint, tag, headers, batchSize, flushIntervalMs, allowInsecure, compress)
    this.appId = appId
    this.appVersion = appVersion
    this.buildNumber = buildNumber
    this.userId = userId
    this.deviceId = resolveOrCreateDeviceId(context)
    this.sessionId = UUID.randomUUID().toString()
    this.schemaV2 = true
}

private fun resolveOrCreateDeviceId(ctx: Context): String {
    val prefs = ctx.applicationContext.getSharedPreferences("traceflow_prefs", Context.MODE_PRIVATE)
    return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
}
```

`emitJson` additions (guarded by `if (schemaV2)`): `schemaVersion=2`, `platform="android-jvm"`, `runtime="jvm-${Build.VERSION.SDK_INT}"`, `appId`, `appVersion`, `buildNumber`, `userId`, `deviceId`, `sessionId`.

`stopRemote()` resets `schemaV2=false, sessionId=null, deviceId=null` (deviceId re-reads from SharedPreferences on next init).

### Acceptance
- Old `startRemote(endpoint, ...)` → events lack `schemaVersion` (v1 semantics preserved).
- New `startRemote(context, endpoint, appId=...)` → events carry `schemaVersion=2, platform, deviceId, sessionId`.
- `deviceId` stable across process restarts; `sessionId` changes per `startRemote` call.

### PR title
`feat(runtime): add Context-aware startRemote overload emitting schema v2 fields`

---

## Step 5 — Studio plugin v2 parse + app picker + platform badge

### Files

Modify:
- [RemoteLogPoller.kt](studio-plugin/src/main/kotlin/io/github/umutcansu/traceflow/studio/remote/RemoteLogPoller.kt) — L90 response parse, L103 `parseEvent`, add `platform/appId/userId` constructor params → query string.
- `studio-plugin/.../model/TraceEvent.kt` — add nullable v2 fields.
- `studio-plugin/.../ui/TraceFlowPanel.kt` — app picker combo, platform combo, userId text field.
- `studio-plugin/.../ui/EventTableModel.kt` — platform badge column (hidden by default).

Create:
- `studio-plugin/.../remote/AppsFetcher.kt` — one-shot GET /apps client.
- `studio-plugin/src/main/resources/icons/platform/` — 8 SVGs (android/reactnative/ios/web × light+dark).
- `studio-plugin/.../ui/PlatformIcons.kt`:

```kotlin
object PlatformIcons {
    val ANDROID = IconLoader.getIcon("/icons/platform/android.svg", javaClass)
    val REACT_NATIVE = IconLoader.getIcon("/icons/platform/reactnative.svg", javaClass)
    val IOS = IconLoader.getIcon("/icons/platform/ios.svg", javaClass)
    val WEB = IconLoader.getIcon("/icons/platform/web.svg", javaClass)
    fun forPlatform(p: String?): Icon? = when (p) {
        "android-jvm"  -> ANDROID
        "react-native" -> REACT_NATIVE
        "ios-swift"    -> IOS
        "web-js"       -> WEB
        else           -> null
    }
}
```

### Grace-parse (defensive)

```kotlin
val parsed = gson.fromJson(body, JsonElement::class.java) ?: return
val events = when {
    parsed.isJsonArray  -> parsed.asJsonArray                                   // v1 legacy server
    parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("events") ?: return
    else -> return
}
val nextCursor = if (parsed.isJsonObject)
    parsed.asJsonObject.get("nextCursor")?.takeIf { !it.isJsonNull }?.asLong else null
```

### Acceptance
- Renders events from v2 server with correct platform badges.
- Still parses legacy v1 server array responses.
- App picker populates from `/apps`; selection narrows event list.
- Platform + userId filters plumb as server query params.

### PR title
`feat(plugin): parse schema v2 events, add app picker, platform badge, and platform/appId/userId filters`

---

## Step 6 — Auth (opt-in) + rate-limit + GDPR delete

### Files
- [build.gradle.kts](sample-server/build.gradle.kts): add `ktor-server-auth`, `ktor-server-auth-jwt`, `ktor-server-rate-limit` (all `:3.1.1`).
- [Main.kt](sample-server/src/main/kotlin/io/github/umutcansu/traceflow/server/Main.kt): wire via new `Auth.kt` and `RateLimit.kt`.

### Opt-in contract

| Env var | Effect when set |
|---|---|
| `TRACEFLOW_INGEST_TOKEN` | `POST /traces` requires `X-TraceFlow-Token` header (constant-time compare). |
| `TRACEFLOW_JWT_SECRET` + `TRACEFLOW_JWT_ISSUER` | Admin endpoints require `Authorization: Bearer <jwt>` (HMAC256, audience check). |

If env vars unset → current behavior preserved (open). Sample `docker-compose.yml` should include commented env var lines as a hint.

### Rate-limit

```kotlin
install(RateLimit) {
    register(RateLimitName("ingest-ip"))    { rateLimiter(limit = 600,    refillPeriod = 60.seconds);  requestKey { it.request.origin.remoteHost } }
    register(RateLimitName("ingest-token")) { rateLimiter(limit = 10_000, refillPeriod = 60.seconds);  requestKey { it.request.headers["X-TraceFlow-Token"] ?: "anon" } }
    register(RateLimitName("admin-ip"))     { rateLimiter(limit = 120,    refillPeriod = 60.seconds);  requestKey { it.request.origin.remoteHost } }
}
```

Wrap POST /traces with nested `rateLimit(...)` blocks (AND semantics). 429 is automatic.

### GDPR delete

```kotlin
delete("/traces") {
    val userId = call.request.queryParameters["userId"]
    val deleted = if (userId != null) deleteByUserId(userId) else clearEvents()
    call.respond(SimpleResponse("deleted $deleted events"))
}
fun deleteByUserId(userId: String): Int =
    transaction { TraceEvents.deleteWhere { TraceEvents.userId eq userId } }
```

### Client impact
- **Android runtime**: zero API change — `RemoteSender.headers` already forwards arbitrary headers.
- **Plugin**: add password field in settings (IntelliJ `PasswordSafe`), send as `Authorization: Bearer ...` on every poll.
- **runtime-js**: already accepts `token` config (Step 7).

### Acceptance
- Env var set → `POST` without header = 401, with header = 200.
- Env var unset → legacy behavior unchanged.
- Admin JWT: GET /traces without Bearer → 401, with valid JWT → 200.
- `DELETE /traces?userId=x` removes only x's rows.
- Flood > 600/min from one IP → 429.

### PR title
`feat(server): opt-in auth (ingest token + admin JWT), IP/token rate limits, GDPR delete`

---

## Step 7 — JS/RN runtime package

### New directory: `runtime-js/`

```
runtime-js/
├── package.json           # @umutcansu/traceflow-runtime
├── tsconfig.json
├── tsup.config.ts
├── src/
│   ├── index.ts           # public API
│   ├── config.ts
│   ├── buffer.ts          # ring buffer
│   ├── sender.ts          # batch + gzip + fetch
│   ├── handlers.ts        # ErrorUtils / window.onerror / unhandledrejection
│   ├── masking.ts         # PII patterns (mirror TraceLog.kt:43)
│   ├── deviceId.ts        # AsyncStorage/localStorage UUID persist
│   ├── storage.ts         # offline queue adapter
│   ├── platform.ts        # detect RN vs web
│   ├── gzip.ts            # CompressionStream + pako fallback
│   └── types.ts           # TraceEvent mirror (Main.kt:22-55)
├── test/
└── examples/node/smoke.mjs
```

### Public API

```ts
export interface TraceFlowConfig {
  endpoint: string;
  appId: string;
  platform: "react-native" | "web-js";
  token?: string;
  appVersion?: string; buildNumber?: string; userId?: string;
  runtime?: string;
  flushIntervalMs?: number;   // default 5000
  batchSize?: number;         // default 50
  maxBufferSize?: number;     // default 1000
  compress?: boolean;         // default true
  maskPatterns?: RegExp[];
  offlineQueue?: boolean;     // default true
}

export function initTraceFlow(cfg: TraceFlowConfig): TraceFlowClient;
export function captureException(err: Error, meta?: Record<string, unknown>): void;
export function trace<T>(name: string, fn: () => T | Promise<T>): T | Promise<T>;
export function setUserId(userId: string | null): void;
export function shutdown(): Promise<void>;
```

### Gzip

```ts
export async function gzip(body: string): Promise<Uint8Array | string> {
  if (typeof CompressionStream !== "undefined") {
    const s = new Blob([body]).stream().pipeThrough(new CompressionStream("gzip"));
    return new Uint8Array(await new Response(s).arrayBuffer());
  }
  try { const pako = await import("pako"); return pako.gzip(body); }
  catch { return body; }  // caller drops Content-Encoding on string
}
```

### Global handlers

- RN: `globalThis.ErrorUtils.setGlobalHandler` (chain previous), `addEventListener('unhandledrejection')`.
- Web: `window.addEventListener('error' | 'unhandledrejection')`.

### Package.json highlights

```json
{
  "name": "@umutcansu/traceflow-runtime",
  "version": "0.1.0",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.mjs",
  "types": "./dist/index.d.ts",
  "sideEffects": false,
  "peerDependencies": { "pako": ">=2.0.0" },
  "peerDependenciesMeta": { "pako": { "optional": true } },
  "publishConfig": { "access": "public" }
}
```

### Smoke test

`examples/node/smoke.mjs` → POSTs to `localhost:4567/traces`, then `curl '…/traces?appId=…&since=0'` round-trips the event.

### Acceptance
- `npm pack` < 50 KB.
- Smoke test round-trips a `CATCH` event with non-empty `stack`.
- With Step 6 `TRACEFLOW_INGEST_TOKEN` set + matching `token` in config → accepted; wrong token → 401.
- `offlineQueue: true` + server unreachable → events persist, flush on next init.

### Publish

```bash
cd runtime-js
npm install && npm run build
npm publish --access public --dry-run
npm publish --access public
```

### PR title
`feat(runtime-js): initial @umutcansu/traceflow-runtime package (phase 1 manual + global handlers)`

---

## Cross-cutting risks

1. **Step 2 breaking shape** — mitigated by same-PR plugin update + grace-parse.
2. **Step 4 API additivity** — keep old `startRemote` overload; no `@Deprecated` yet.
3. **Step 6 opt-in default** — sample-server stays open out-of-the-box; document prod env vars.
4. **Ktor rate-limit maturity** — keep config minimal; fallback to hand-rolled middleware if bugs.
5. **npm name squat** — reserve `@umutcansu/traceflow-runtime` now with 0.0.1 placeholder.
6. **Marketplace release timing** — do not bump plugin 2.0 to Marketplace until `feat/multi-platform-v2` is merged to `main`.
7. **SQLite writer contention** — rate-limits help; real production path is Postgres (plan doc §3).

---

## Verification checklist

- [ ] Android v1 client (old signature) → accepted, no `schemaVersion`.
- [ ] Android v2 client (new overload) → `schemaVersion=2, platform, deviceId, sessionId`.
- [ ] GET /traces paginates: `since=nextCursor` reaches next page.
- [ ] GET /apps returns per-app summary.
- [ ] Plugin renders platform badges for all 4 platforms.
- [ ] Plugin parses both v1 (array) and v2 (envelope) server responses.
- [ ] Ingest auth: 401 without token when env set.
- [ ] Admin JWT auth: 401 without Bearer when env set.
- [ ] Rate-limit: 429 after flood.
- [ ] `DELETE /traces?userId=x` removes only x's rows.
- [ ] runtime-js smoke round-trips event.
- [ ] runtime-js captures unhandled RN throw with `stack`.
