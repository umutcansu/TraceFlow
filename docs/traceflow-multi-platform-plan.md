# TraceFlow çoklu-platform desteği — geliştirme planı

Bu plan **TraceFlow repo**'sunda (github.com/umutcansu/TraceFlow)
uygulanacak değişiklikleri tanımlar. Amaç: TraceFlow'un Android
bytecode instrumentation odaklı yapısını, platform-bağımsız bir
**trace ingestion servisi**ne dönüştürmek. Bu sayede React Native
(Hermes JS VM), iOS (Swift), web (TypeScript) gibi non-JVM istemciler
de aynı backend'e event gönderebilir ve mevcut Android Studio plugin
ile görüntülenebilir.

Mevcut durum:

- `sample-server` Ktor 3.1.1 + **SQLite (Exposed ORM)** üzerine kurulu;
  sadece Android runtime'dan gelen bytecode-enjeksiyon tabanlı
  event'leri kabul ediyor.
- **Server-side auth yok** — `RemoteSender` custom header yollayabilir
  ama server hiçbir şey doğrulamıyor.
- **Transport gzip destekli** (commit `3b82662`): `Content-Encoding: gzip`
  ile POST kabul edilir, `Accept-Encoding: gzip` ile yanıt sıkıştırılır
  (Ktor `Compression` plugin).
- Schema Kotlin reflection tipine yakın; `platform` / `appId` /
  `sessionId` ayrımı yok, multi-tenant değil.

### Branch stratejisi

Bu plan **yeni bir feature branch** üzerinde uygulanır — `main`'de
Android bytecode akışı hiç etkilenmesin:

```bash
git checkout -b feat/multi-platform-v2
```

- Her alt madde (Schema v2 / Runtime v2 / Plugin v2 / Auth / JS
  runtime) için ayrı PR açılır; hepsi `feat/multi-platform-v2`
  üzerine merge edilir.
- Kullanıcı mevcut `main`'den almaya devam edebilir; hiçbir mevcut
  sürüm (`runtime-1.x`, plugin 1.x) bu branch'te kırılmaz.
- `feat/multi-platform-v2` tamamlanınca tek seferde `main`'e merge
  + major bump (`runtime 2.0.0`, plugin 2.0.0).

---

## 1. Event schema genişlemesi

Mevcut alanlara ek olarak **opsiyonel** alanlar eklemek gerekli.
Geriye uyumluluk: Android bytecode client'ı boş bırakır, geçer.

> **Alan adı kararı (A)**: v1'deki `result` ve `durationMs` adları
> v2'de **olduğu gibi korunur**. JS/iOS runtime aynı adları kullanır.
> Yeniden adlandırma yapılmaz; böylece mevcut Android client ve
> plugin parser'ı değiştirilmeden çalışır. Kaynak:
> `runtime/.../TraceLog.kt:139,156` (`emitJson("EXIT", ...)`);
> `sample-server/.../Main.kt:35-36` (`result: String? = null`,
> `durationMs: Long? = null`).

```jsonc
{
  // ─── Mevcut alanlar (hiç değişmedi) ───────────────────
  "type":               "ENTER | EXIT | CATCH | BRANCH",
  "class":              "string",
  "method":             "string",
  "file":               "string",
  "line":               "number",
  "threadId":           "number",
  "threadName":         "string",
  "ts":                 "number (epoch ms)",
  "deviceManufacturer": "string",
  "deviceModel":        "string",
  "tag":                "string",
  "params":             "object",
  "result":             "any",           // mevcut alan adı (v1 ile aynı; 'returnValue' değil)
  "durationMs":         "number (ms)",   // mevcut alan adı (v1 ile aynı; 'duration' değil)
  "exception":          "string",

  // ─── YENİ, opsiyonel ──────────────────────────────────
  "stack":              "string[] (JS exception'lar için structured stack; Android'de opsiyonel)",
  "proguardMapId":      "string (Android release build obfuscated stack demap için — JS 'sourceMapId' karşılığı)",
  "platform":           "android-jvm | react-native | ios-swift | web-js",
  "runtime":            "string (ör: 'hermes-0.12.0', 'jvm-17', 'swift-5.9')",
  "appId":              "string (ör: 'com.bufraz.parla', 'com.myapp.sample')",
  "appVersion":         "string (semver)",
  "buildNumber":        "string",
  "userId":             "string | null",
  "deviceId":           "string (anonim uuid, 1. açılışta üretilir)",
  "sessionId":          "string (uygulama oturumu uuid)",
  "sourceMapId":        "string (JS bundle hash — stack demap için)",
  "isMinified":         "boolean (JS tarafında true → stack demap gerek)"
}
```

### Masking & privacy fields

- `params` ve `result`'ta regex mask zaten var — sözleşmeyi
  `sensitivePatterns` config paketinde döşe (default listesi: `password`,
  `token`, `jwt`, `api[_-]?key`, `email`, `phone`, `creditcard`).

### Schema versiyonlama

- Event'e `schemaVersion: 2` ekle (mevcut TraceFlow Android runtime
  `1` gönderir ya da hiç göndermez).
- Server minor version drift'te event'i kabul eder, tanımadığı alanları
  saklar/forward'lar.

---

## 2. HTTP API genişlemesi

Mevcut iki endpoint korunur, ekleme yapılır.

### `POST /traces`
- **Değişmedi**: aynı array-of-events body. Yeni alanlar opsiyonel.
- **Auth (yeni — şu an hiç auth yok)**:
  - `X-TraceFlow-Token: <shared-token>` — client-side SDK'lar için
    (build-time config; `BuildConfig`/`.env` üzerinden enjekte edilir)
  - `Authorization: Bearer <admin-jwt>` — dashboard/viewer için
  - ⚠️ **Güvenlik uyarısı**: Mobil istemcilerde shared-token
    `BuildConfig`'e gömülse dahi APK/IPA reverse-engineering ile
    çıkarılabilir. Hassas veri akan kurulumlar için kısa ömürlü JWT +
    backend proxy, veya sadece anon ingestion + admin tarafta gerçek
    auth değerlendirilmeli.
- **Rate-limit** (başlangıç değerleri; gerçek ölçüm sonrası kalibre
  edilecek — uygulama × cihaz × event/s): IP başına ~600/dk, token
  başına ~10k/dk. Aşımda `429 Too Many Requests`.

### `GET /traces`
- **Genişletilmiş query string**:
  - `since=<ts>` (mevcut)
  - `until=<ts>` — range filter
  - `platform=<value>`
  - `appId=<value>`
  - `userId=<value>`
  - `deviceId=<value>`
  - `level=<ENTER|EXIT|CATCH|BRANCH>`
  - `tag=<value>`
  - `limit=<n>` (default 500, max 5000)
- Cevap: `{ events: [...], nextCursor: <ts> }` (pagination).

### Yeni: `GET /sources/<sourceMapId>`
- JS bundle source map deposu. Client minified stack gönderir, server
  demap edip IDE viewer'a demap edilmiş stack sunabilir.
- Upload: `POST /sources` — base64 gzipped .map dosyası, appId+
  buildNumber+hash ile indexlenir.
- Android bytecode tarafı bu endpoint'i kullanmaz; no-op.

### Yeni: `GET /apps`
- Server'a event yollamış tüm uygulamaların özeti. Plugin'de
  "app picker" olmasını sağlar.

### Transport encoding (mevcut — 2025-04 itibarıyla deploy edildi)
- İstemciler `Content-Encoding: gzip` ile POST atabilir; server
  `GZIPInputStream` ile açar (`Main.kt` POST `/traces` handler).
- Server `Accept-Encoding: gzip` alan istemcilere yanıtı gzip'le
  döner (Ktor `Compression` plugin — `gzip()` + `deflate()`).
- **JS/RN runtime notu**: modern ortamlarda `CompressionStream`
  (`new Response(body).body.pipeThrough(new CompressionStream('gzip'))`);
  eski RN/Hermes'te `pako` fallback. JVM ve iOS URLSession yanıtı
  otomatik decode eder — ekstra iş yok.
- Referans commit: `3b82662 Add gzip compression for trace event transport`.

---

## 3. Storage layout

Mevcut SQLite (Exposed ORM) tek-process için yeterli ama çok-tenant
ingestion için scaling yetersiz. Önerilen:

- **PostgreSQL tablo**: `trace_events (id, platform, app_id, user_id,
  device_id, session_id, ts, type, class, method, exception, params,
  return_value, ...)`. Partitioning: `PARTITION BY RANGE (ts)` — günlük.
- **Redis sorted set** (ingestion buffer): `trace:buffer:<appId>` —
  bir yazıcı thread tarafından Postgres'e flush.
- Retention: configurable default 14 gün. Postgres `DROP PARTITION`.

### GDPR / PII

- `userId` ve `params` alanları PII taşıyabilir. Retention politikası
  kullanıcıya dökümante edilmeli.
- Yeni endpoint: `DELETE /traces?userId=<value>` — right-to-erasure
  (GDPR Art. 17) talepleri için; admin JWT ister, ilgili satırları
  tüm partition'lardan siler.
- PII masking kuralları (`sensitivePatterns`) her iki client'ta
  (Android + JS) aynı listeyi kullanmalı (DRY — schema sabiti).

Alternatif: SQLite — tek-process geliştirme için yeter; production
Postgres önerilir.

> **İsim kararı**: Postgres + Redis + auth + rate-limit eklenince
> "sample-server" modül adı yanıltıcı olur. Production servis yeni
> bir Gradle modülü olarak ayrılsın: `ingestion-server/`.
> `sample-server/` saf demo/smoke-test olarak kalır (SQLite, auth'suz).

---

## 4. IDE plugin güncellemesi

Mevcut plugin `/traces` çağırıp Android event'lerini gösteriyor.
İki yeni UI elemanı + filter panel genişlemesi:

1. **App picker** dropdown — `GET /apps` response'undan doldurulur.
   Seçilen `appId` tüm sonraki `/traces` poll'larına query olarak
   eklenir. Yeni komponent (~100 satır Kotlin UI).
2. **Platform ikon seti** — Android / RN / iOS / Web için 4 ikon ×
   light+dark tema (8 asset) + enum→icon mapping. Event listesinin
   solunda rozet olarak render.
3. **Filter panel genişlemesi** — mevcut filter infrastructure'ına
   `platform`, `appId`, `userId` alanları eklenir (sıfırdan değil;
   mevcut `tag` filter'ı pattern'i takip eder).
4. **`TraceEvent` parse genişlemesi** ([RemoteLogPoller.kt:103](
   studio-plugin/src/main/kotlin/io/github/umutcansu/traceflow/studio/remote/RemoteLogPoller.kt))
   — schema v2 opsiyonel alanları (`platform`, `appId`, `sessionId`,
   `stack`, ...) parse edilip `TraceEvent.extra`'ya veya yeni
   field'lara eşlenmeli.

### Dağıtım

Plugin **JetBrains Marketplace** üzerinden dağıtılmaya devam eder
(mevcut: https://plugins.jetbrains.com/plugin/30959-traceflow).
v2 değişiklikleri yeni bir minor sürüm olarak push edilir:

```bash
cd studio-plugin
ORG_JETBRAINS_INTELLIJ_PLATFORM_PUBLISH_TOKEN=<token> \
  ./gradlew publishPlugin
```

Kullanıcı Android Studio'nun **Updates** sekmesinden otomatik
güncellenir; yeni bir kurulum adımı yoktur.

---

## 5.0 Android runtime'da gereken değişiklikler

Schema v2 Android tarafında da **breaking** değişiklik getirir —
plan bunu hafife almamalı:

- **`TraceLog.startRemote` imzası değişir**: şu an Context almıyor
  ([TraceLog.kt:79](runtime/src/main/java/io/github/umutcansu/traceflow/TraceLog.kt)).
  `deviceId` auto-UUID'i ilk açılışta üretip `SharedPreferences`'a
  kalıcılaştırmak için `Context` parametresi şart. Bu public API
  kırılması — major version bump (`2.0.0`) gerektirir.
- **`emitJson` payload genişlemesi** ([TraceLog.kt:201](
  runtime/src/main/java/io/github/umutcansu/traceflow/TraceLog.kt)):
  `platform="android-jvm"`, `runtime="jvm-${Build.VERSION.SDK_INT}"`,
  `appId`, `appVersion`, `buildNumber`, `sessionId`, `schemaVersion=2`
  alanları eklenir. Boyut artışı gzip ile soğurulur (madde 2 transport).
- **ProGuard/R8 mapping upload**: Release APK'da class/method
  obfuscate olduğundan trace okunamaz. Gradle plugin'e
  `traceflow { uploadMappingOnBuild = true }` config'i + build-time
  upload task'ı eklenir; event'e `proguardMapId` eklenir (R2 ile
  bağlantılı). Server `/sources` endpoint'ini `/mappings` ile
  genelleştir veya ayrı endpoint (`POST /mappings`) ekle.

---

## 5. JS/RN runtime (ayrı bir paket önerisi)

TraceFlow Android runtime'ına paralel bir **TraceFlow-JS runtime**
paketi planlanabilir (ayrı repo veya `runtime-js/` alt dizin):

- `@traceflow/js-runtime` npm paketi.
- `initTraceFlow({ endpoint, token, appId, platform })` tek çağrı.
- Ring buffer + 5s flush + AppState=background flush.
- Offline queue: AsyncStorage (RN) / IndexedDB (web).
- Global handler hook'ları:
  - `ErrorUtils.setGlobalHandler` (RN)
  - `window.onerror` / `unhandledrejection` (web)
  - React `ErrorBoundary` helper.
  - `console.error/warn` monkey-patch.
- PII masking shared lib (TraceFlow Android'daki mask kuralları JS
  tarafında da geçerli — DRY için schema sabitini paylaş).

### Dağıtım (JS runtime)

**npm public registry** üzerinden yayınlanır. Paket adı için iki
aşamalı yaklaşım:

| Aşama | Paket adı | Gerekçe |
|---|---|---|
| İlk yayın | `@umutcansu/traceflow-runtime` | Personal scope, npm org gerektirmez — hemen başlanabilir |
| Stabil | `@traceflow/js-runtime` | `traceflow` npm org'u alınınca re-publish; eski paket `npm deprecate` ile redirect |

Yayın akışı:
```bash
cd runtime-js
npm version <patch|minor|major>
npm publish --access public
# CI: git tag push'ta GitHub Actions ile otomatik
```

Kullanıcı entegrasyonu:
```bash
yarn add @umutcansu/traceflow-runtime
# App.tsx
import { initTraceFlow } from '@umutcansu/traceflow-runtime';
initTraceFlow({
  endpoint: 'https://server/traces',
  appId: 'com.bufraz.parla',
  platform: 'react-native',
  token: process.env.TRACEFLOW_TOKEN,
});
```

Babel plugin (Phase 2) ayrı paket: `@umutcansu/traceflow-babel-plugin`
(devDependency, `babel.config.js` plugins listesine eklenir).

### Opsiyonel Phase 2: auto-instrumentation Babel plugin

TraceFlow Android bytecode injection'ının JS eşdeğeri. Ayrı paket:
`@traceflow/babel-plugin`.

- AST traversal → her fonksiyonu `withTrace()` wrap'iyle sar
  (arrow, async, generator, class method, anonymous expr — her
  biri ayrı AST case)
- Async/Promise path
- Generator
- `/* @notrace */` yorumu opt-out, dosya-level ve blok-level de
  (TraceFlow'daki `@NotTrace` karşılığı)
- Metro transformer entegrasyonu + cache invalidation
- Source map yeniden üretimi (orijinal satır/sütun korunmalı)
- TypeScript parse (Babel preset-typescript)
- Runtime aynı event schema'ya push eder.

**Tahmini**: **3-6 hafta** ayrı geliştirme (AST case çeşitliliği +
source map + Metro cache + TS). Phase 1 (error capture) onsuz da
kullanışlı — Phase 2 teslimat baskısı olmadan ayrı bir milestone.

---

## 6. İstemci entegrasyon adımları

Bu adımlar TraceFlow repo kapsamının **dışında** — istemci
uygulamaların kendi repolarında yapılır. Referans için:
`parla-mobile/docs/traceflow-integration.md` (bu repoya commit
edilmez; ilgili istemci ekibi tutar).

Tipik entegrasyon şekli:
1. `@traceflow/js-runtime` yayınlanınca istemci `package.json`'a eklenir.
2. Uygulama girişinde `initTraceFlow({ endpoint, token, appId,
   platform })` çağrılır.
3. `ErrorBoundary` / global handler `traceflow.captureException(err)`
   kullanır.

---

## 7. Test kurulumu

- `docker compose -f sample-server/docker-compose.yml up` ile
  sample-server'ı lokalde 8080 portunda çalıştır.
- Android Studio plugin base URL → `http://localhost:8080`.
- Parla'dan kasıtlı hata fırlat → 5 saniye içinde plugin'de görünür.

### Verification checklist

- [ ] Android bytecode client eski gibi çalışıyor (`platform=android-jvm`
      gönderiyor)
- [ ] RN client error gönderebiliyor, plugin'de `platform=react-native`
      rozetiyle görünüyor
- [ ] `GET /apps` doğru listeliyor
- [ ] Source map upload çalışıyor; RN stack demap edilip gösteriliyor
- [ ] Rate limit 10k/dk'ı aşan client 429 alıyor
- [ ] PII masking her iki platformda da aynı kuralları uyguluyor
- [ ] Retention: 14 günden eski event'ler partition drop ile siliniyor

---

## 8. İş sırası önerisi

1. **Schema v2 + API + storage** (3-4 gün) — `schemaVersion=2`,
   yeni opsiyonel alanlar, POST genişletmesi, GET filter'ları,
   storage katmanı. Schema/API/storage birlikte değişir; tek iş
   kalemi olarak planla.
2. **Android runtime v2 + ProGuard mapping upload** (2-3 gün) —
   `TraceLog.startRemote(context, ...)` breaking change, schema v2
   alanları, `@traceflow/gradle-plugin`'de mapping upload task'ı.
3. **Plugin App picker + platform rozeti + v2 parse** (1-2 gün).
4. **Auth + rate-limit + GDPR delete endpoint** (2-3 gün) — shared
   token + admin JWT + Ktor rate-limit plugin + `DELETE /traces`.
5. **`@traceflow/js-runtime` npm paketi** (3-4 gün) — Phase 1 sadece
   manuel API + error handler + gzip transport.
6. İstemci entegrasyonları (bkz. madde 6) — paket yayınlanınca
   istemci ekipleri kendi repolarında halleder.
7. **Phase 2: Babel plugin** (opsiyonel, 3-6 hafta — bkz. madde 5).

Paralel çalışabilen maddeler: 3 ↔ 5 (plugin UI ve JS runtime
birbirinden bağımsız). Toplam (Phase 1 kapsamı) ~2-3 hafta.
