# TraceFlow — Ürün Vizyonu ve Yol Haritası

> **TL;DR**: TraceFlow'u Sentry'nin birebir klonu yapmak ~2 yıl ve çok
> büyük ekip ister, ve Sentry zaten açık kaynak. Rekabetçi olma yolu
> **klonlamak değil, tamamlayıcı bir ürün** olmaktır:
> "Android bytecode seviyesinde, self-hosted, data-sovereign
> debugging-level observability". Sentry "hata raporlama + APM" için,
> TraceFlow "zero-code method-level tracing" için.

## 1. Mevcut durum (snapshot)

| Kategori | Sentry | TraceFlow (bugün) |
|---|---|---|
| Error tracking | ✅ olgun | ⚠️ temel — CATCH event kabul ediyor, dedup/issue grouping yok |
| Performance/APM (tracing) | ✅ span/waterfall | ⚠️ ENTER/EXIT çifti var, waterfall/flame graph yok |
| Session replay | ✅ DOM kayıt | ❌ yok |
| Release tracking | ✅ source map + commit | ⚠️ plan var, implement yok |
| Breadcrumbs | ✅ otomatik | ❌ yok |
| Alerting | ✅ email/Slack/PagerDuty | ❌ yok |
| Web dashboard | ✅ | ❌ sadece IntelliJ plugin |
| Kullanıcı/takım yönetimi | ✅ RBAC, SSO | ❌ tek kullanıcı |
| SDK dilleri | 100+ | Android JVM + RN/Web JS (bugün) |
| On-prem | ✅ Docker Compose | ⚠️ sample-server var, prod-ready değil |
| **Benzersiz güç** | — | 🎯 **Bytecode-level zero-code tracing** |

## 2. Pozisyonlama — Sentry ile yarışmak mı, onu tamamlamak mı?

### Birebir Sentry klonu yapmak akıllıca değil

- Sentry zaten open source (sentry-self-hosted, Apache 2.0)
- ~1000 kişilik mühendislik ekibi var, 10 yıldır geliştiriyor
- "Bedava + olgun + hosted variant" ile yarışmak imkansıza yakın
- Pazar Sentry, Datadog, New Relic tarafından doymuş

### Farklılaşma — TraceFlow'un gerçek avantajı

**"Debugging-level observability"**: Sentry bir fonksiyona açıkça eklediğin
`Sentry.captureException()` çağrısını gösterir. TraceFlow **hiç kod
değişikliği olmadan** her method'un entry/exit/catch/branch'ini gösterir.

Bu teknik olarak farklı bir ürün kategorisi:

| Sentry | TraceFlow |
|---|---|
| Production error tracking | Staging/debug tracing |
| Sample rate %1 typical | Her method, her çağrı |
| "Hata olduğunda ne oldu" | "Her zaman ne oluyor" |
| SDK explicit çağrılar | Bytecode ASM inject |
| Event count / GB / transaction tabanlı ücretlendirme | Self-hosted, ücretsiz |
| Multi-tenant SaaS | Single-tenant / data-sovereign |

**Sentry'nin zayıflığı**: Android native method tracing, build-time
bytecode visibility. Bunu yapmak SDK-based enstrümantasyonla çok ağır;
build-time inject ile doğal.

### Kime satıyoruz / kim ilgilenir?

1. **Regülated ortamlar** (bankalar, healthcare, devlet) — veri dışarı
   çıkamaz, self-hosted şart. Sentry self-hosted OK ama TraceFlow +
   SQLite/Postgres daha hafif.
2. **Debug-yoğun ekipler** — flaky test, zor tespit edilen hatalar.
   "Son 30 saniyede tüm method'ları göster" isteyen QA ekipleri.
3. **Android-first şirketler** — Sentry Android SDK iyidir ama
   bytecode-level "her metoda gir" yeteneği yok.
4. **Kendi Sentry'ini kurmak istemeyenler** — sentry-self-hosted
   Kubernetes + Postgres + Redis + Kafka + ClickHouse. TraceFlow
   tek binary + SQLite.

### Slogan fikirleri

- "Sentry for the methods Sentry doesn't see"
- "Zero-code observability for Android"
- "Debug-level tracing, self-hosted"

## 3. MVP — İlk 6 ay (ürün olarak satılabilir minimum)

Hedef: Sentry'e "her şey"de karşılık veremeyiz ama bir kullanıcının
"bunu Sentry yerine deneyeyim" demesini sağlayacak minimum fonksiyon:

### 3.1 Web dashboard (IntelliJ plugin dışında)

Şu an IntelliJ plugin tek client. Gerçek bir ürün için **web dashboard**
şart:

- **Event list / search** (plugin'deki gibi ama tarayıcıda)
- **Issue view** — aynı exception stack'i tek issue'ya grupla (fingerprinting)
- **User/session timeline** — bir kullanıcının son N event'i
- **Basic filters** — time range, platform, appId, userId, level, tag
- **Stack trace viewer** (source map demap sonrası)

Tech: Next.js / SvelteKit + shadcn/ui. Server API'sine bağlanır.
~4-6 hafta tek geliştirici.

### 3.2 Issue grouping / fingerprinting

Sentry'nin en sevilen özelliği. Aynı hata 1000 kez olursa 1000 event
değil **1 issue + 1000 occurrence**.

Fingerprint algoritması:
- `CATCH` event'leri için: `hash(exception.type + top-N-stack-frames)`
- `ENTER` için gerek yok (trace'leri gruplamak mantıksız)

Schema:
```sql
CREATE TABLE issues (
  id UUID PRIMARY KEY,
  app_id TEXT, platform TEXT,
  fingerprint TEXT,
  title TEXT,           -- "TypeError in App.render"
  first_seen TIMESTAMP,
  last_seen TIMESTAMP,
  event_count INT,
  user_count INT,
  status TEXT,          -- unresolved|resolved|ignored
  UNIQUE (app_id, fingerprint)
);
-- events table gains issue_id FK
```

~2 hafta iş.

### 3.3 Source map + ProGuard mapping upload/demap

Release tarafında minified stack'leri decode etmek:

- `POST /releases` — release metadata (app_id, version, build_number, commit_sha)
- `POST /releases/:id/sourcemaps` — JS source map upload (gzipped .map)
- `POST /releases/:id/proguard` — Android R8 mapping.txt upload
- Ingest-time: release eşleşiyorsa stack'i otomatik demap et, saklarken
  both raw ve demapped versiyonları tut

Gradle plugin + Jenkinsfile + CI entegrasyonu: build sonrası auto-upload.

~3-4 hafta.

### 3.4 Alerting (temel)

Yeni issue oluştuğunda, eski bir issue tekrar açıldığında, bir issue
X saatte Y kez olduğunda bildirim:

- **Email** (SMTP config)
- **Webhook** — Slack/Discord/kendi sistem
- **PagerDuty integration** sonraya

Basit kural motoru, YAML ile:
```yaml
rules:
  - name: Payment errors spike
    when: issue.app_id == "com.shop.pay" AND occurrences_last_5m > 10
    notify: [slack-channel-1, email-dev-team]
```

~2 hafta.

### 3.5 Project / team / user management

Tek kullanıcıdan çok kiracıya geçiş:

- **Organizations** → üst seviye
- **Projects** (appId'nin olgun hali) — her proje ayrı ingest token
- **Users** (login, RBAC: admin/member/viewer)
- **SSO** (Google / GitHub OAuth) — sonraya bırak, ilk sürümde
  email/password yeter

DB schema eklemesi + admin UI. ~3 hafta.

### 3.6 Deployment story

- **Docker Compose** (`docker-compose.yml` içinde server + Postgres +
  Redis + reverse proxy)
- **Helm chart** (k8s için)
- `.env.example` → config dökümantasyonu
- **Tek komutla kurulum**: `curl install.sh | bash`

~2 hafta.

**MVP toplam**: 3.5 - 4.5 ay × 2 geliştirici, veya 6-7 ay × 1.

## 4. Faz 2 — 6-12 ay (ürün pazara hazır)

MVP üzerine:

### 4.1 Performance/tracing gerçek APM

- **Distributed traces** — trace_id + parent_span_id ilişkisi
- **Waterfall view** — bir request'in tüm span'leri zaman ekseninde
- **p95/p99 latency** — method düzeyinde
- **Trace sampling** — her event'i saklamak pahalı, %1 sample + her
  CATCH %100

### 4.2 Breadcrumbs

Hatadan önceki son 100 event'i otomatik topla:

```
[breadcrumb] 13:22:01 ENTER MainActivity.onCreate
[breadcrumb] 13:22:05 ENTER ApiClient.fetch(url=/users)
[breadcrumb] 13:22:06 EXIT  ApiClient.fetch -> 200
[breadcrumb] 13:22:07 ENTER UserListFragment.onViewCreated
[error]      13:22:08 CATCH NullPointerException @ UserListFragment:42
```

Ring buffer pattern, storage'da son N saat.

### 4.3 iOS Swift runtime

SwiftSyntax macro ile compile-time instrumentation. En zoru macOS
dependency + Swift compiler plugin. 2-3 ay ayrı iş.

### 4.4 Sentry SDK uyumluluk katmanı

**Büyük lever**: Sentry'nin `Sentry.captureException()` API'sine HTTP
uyumlu bir endpoint sun. Böylece Sentry kullanan her proje,
`dsn` değiştirerek TraceFlow'a geçer.

- `POST /api/0/envelope/` endpoint'i — Sentry protocol implementation
- Event format translation — Sentry event shape → TraceFlow schema

"Drop-in Sentry replacement" → müthiş bir hook.

### 4.5 Hosted version (beta)

Self-hosted varyanta ek olarak, yönetilen SaaS:
- `traceflow.io` (veya domain alırız) → sign up, project oluştur, DSN al
- Freemium: 5K event/gün ücretsiz, üstü $20/ay
- Underlying: TraceFlow'un kendi kodu multi-tenant modda

Bu karar strateji noktası — **self-hosted açık kaynak kalır, hosted
ticari**. Sentry'nin modeli aynısı.

## 5. Faz 3 — 12-24 ay (platform)

### 5.1 Session replay
DOM/view kaydı. Zor ama farklılaştırıcı.

### 5.2 Profiling (continuous)
Sürekli CPU/memory profile → flame graph. Android için Perfetto
entegrasyonu, iOS için Instruments.

### 5.3 Plugin ekosistemi / integrations
- GitHub → issue → PR linki
- Jira → auto-create ticket
- Linear, Slack, Discord otomasyonu
- Terraform provider (alerts as code)

### 5.4 ML-powered anomaly detection
"Bu endpoint'in latency'si normalde 50ms, şu an 500ms" otomatik alert.

## 6. Teknik kararlar

### Storage migration yolu

| Aşama | Storage | Rasyonel |
|---|---|---|
| Şu an | SQLite | Dev/demo yeter |
| MVP | PostgreSQL + partitioning by date | İlk prod deploy'lar |
| Faz 2 | Postgres + ClickHouse (event store) | Event volume > 100k/gün |
| Faz 3 | Clickhouse + Redis + Kafka | Gerçek scale |

### API tasarımı

- REST + JSON (bugünkü yaklaşım) MVP için
- gRPC / protobuf yüksek-throughput ingest için Faz 2'de
- GraphQL dashboard için Faz 2'de

### Frontend stack

- **Next.js 15 + shadcn/ui + Tailwind** — modern, hızlı, community
- Alternatif: SvelteKit — daha hafif ama ekosistem küçük
- Chart'lar: Recharts / Tremor

### Auth

- MVP: email/password + JWT session
- Faz 2: SSO (Google/GitHub), SAML (enterprise için)

## 7. Ekip ihtiyacı

Dürüst değerlendirme — mevcut kurulum (1-2 geliştirici) ile neler
olabilir:

| Rol | MVP (6 ay) | Faz 2 (12 ay) |
|---|---|---|
| Backend (Kotlin/JVM) | 1 (mevcut) | 2 |
| Frontend (React/Next) | **1 eksik** | 1 |
| Mobile SDK (Android/iOS) | 1 (mevcut, RN/Web tarafı) | 2 |
| DevOps / SRE | 0.5 | 1 |
| Design / UX | freelance | 1 part-time |

**Kritik gap**: Frontend geliştirici yok. Web dashboard Phase 1'in
olmazsa olmazı. Ya işe alım ya da freelance.

## 8. Monetizasyon

| Model | Pro | Con |
|---|---|---|
| **Self-hosted only, açık kaynak** | Topluluk kazanır, hızlı büyür | Para yok, sürdürülebilirlik zor |
| **Open core** — temel özellik açık, SSO/teams/audit-log kapalı | Sentry'nin modeli, çalıştığı kanıtlı | "Business source" lisans tartışması |
| **SaaS + open source self-hosted** | İkisi paralel (Sentry tam böyle) | Operasyon overhead'i büyük |
| **SaaS only** | Basit | Open source topluluk olmaz |

**Önerilen**: açık kaynak self-hosted + open-core enterprise add-on'lar
+ Faz 2'de SaaS. Önce community, sonra para.

Fiyatlandırma (hosted Faz 2):
- **Free**: 10K event/ay
- **Pro $29/ay**: 1M event/ay, 30 gün retention
- **Team $99/ay**: 10M event/ay, SSO, team features
- **Enterprise**: custom — self-hosted + support

## 9. Riskler

| Risk | Olasılık | Etki | Azaltma |
|---|---|---|---|
| Sentry aynı özellikleri ekler | Yüksek | Yüksek | Farklılaşma (bytecode) net tutulmalı |
| Tek geliştirici burnout | Yüksek | Kritik | MVP öncesi 1-2 kişi daha |
| Hosting / ops maliyeti | Orta | Orta | SaaS'ı Faz 2'ye ertele |
| Kullanıcı adoption zayıf | Yüksek | Yüksek | HackerNews Show / ProductHunt launch, open core community |
| Sentry SDK uyumluluk hukuki | Düşük | Orta | Apache 2.0 sentry code'u biz implemente etmeyiz, protocol'ü destekleriz |
| Storage maliyeti katlanır | Orta (Faz 2) | Orta | Event sampling + retention policies |

## 10. İlk 90 gün — somut aksiyon planı

### Ay 1: Temel sağlamlaştırma
- [ ] v2 branch'i main'e merge + 2.0.0 release (Android runtime, plugin, runtime-js)
- [ ] Test altyapısı kur (JUnit server için, vitest runtime-js için, instrumented test runtime için)
- [ ] GitHub Actions'ta PR validation workflow (build + test + lint)
- [ ] Docs temizliği: README v2 güncelleme, CHANGELOG, CONTRIBUTING.md
- [ ] Sample-server'ı prod-hazır: Docker image, Postgres support, healthcheck

### Ay 2: Web dashboard iskeleti
- [ ] Next.js projesi kur — `web/` alt dizin
- [ ] Auth (email/password + JWT)
- [ ] Event list sayfası (server'ın GET /traces endpoint'i üstüne)
- [ ] Organization/Project CRUD
- [ ] Tek-proje deploy: `docker-compose.yml` → server + web + Postgres

### Ay 3: Issue grouping + release tracking
- [ ] Fingerprint algoritması (CATCH event'leri grupla)
- [ ] Issue list + detail sayfa
- [ ] Source map upload endpoint + demap pipeline
- [ ] ProGuard mapping upload
- [ ] İlk alpha release: `traceflow/traceflow:0.2.0-alpha` Docker image
- [ ] Launch: HackerNews Show, Android dev subreddit, Twitter/BlueSky

### İlk 90 gün çıktısı

Alpha release + 3-5 early adopter + feedback loop.

## 11. Sınırlar / ne yapmıyoruz

- ❌ Sentry'nin birebir API klonu (SDK layer Faz 2'de, ama ilk sürümde değil)
- ❌ iOS runtime (Faz 2)
- ❌ Distributed trace correlation (OpenTelemetry export Faz 2)
- ❌ Session replay (Faz 3)
- ❌ Commercial support contracts (Faz 2+)
- ❌ Kubernetes operator (k8s Helm chart yeter)

## 12. Başarı kriterleri

### MVP sonu (6. ay)
- GitHub: ≥ 500 star
- Docker pulls: ≥ 5K
- Self-hosted deploy'lar: ≥ 50 (telemetry opt-in ile ölçülür)
- Aktif dev topluluk: Discord/Slack ≥ 100 üye

### Faz 2 sonu (12. ay)
- GitHub: ≥ 2K star
- Aktif kurulum: ≥ 500
- İlk 10 ödeme yapan müşteri (hosted beta)
- MRR: $1K (küçük ama başlangıç)

### Faz 3 sonu (24. ay)
- MRR: $25K+
- Self-hosted: 5K+ kurulum
- Ekip: 4-6 tam zamanlı

---

## Sonuç

**Evet, "Sentry gibi" bir şey yapabiliriz — ama Sentry'nin klonu
olmamalı.** TraceFlow'un gerçek kozu **bytecode-level zero-code
tracing**. Bu angle'ı koruyarak:

1. Önce community tool (6-12 ay)
2. Sonra open-core + hosted (12-24 ay)
3. Paralelde enterprise/compliance market

Kaynakla çakıştığı alanlar (web dashboard, issue grouping, alerting) —
bunları minimum şekilde yapıp farklılaşmanı koruyorsun. Daha fazla
değil.

**Sıradaki karar noktası**: 90 günlük plandan hangi maddeleri şimdi
başlatalım? Ya da: önce v2 merge + CHANGELOG + gerçek test altyapısı
mı? (Bence budur — sağlam temel olmadan ürün planı havada.)
