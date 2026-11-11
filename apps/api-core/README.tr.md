# Pulseboard api-core

[English](README.md) | Türkçe

Pulseboard'un backend servisi: kimlik ve kiracılık (tenancy), metrik alımı ve sorgulama, servis ve metrik kataloğu, anomaliler ve bunların canlı akışı, alarm kuralları ve webhook teslimi.
Java 25, Spring Boot 4.1, sanal thread'ler üzerinde Spring Web MVC, Spring Security (JWT), Hibernate'li Spring Data JPA, Flyway, PostgreSQL 18 ve Redis 8.

Tarayıcının ve metrik ajanlarının konuştuğu tek bileşen budur. `analytics` servisi ise imzalı bir dahili uç nokta üzerinden api-core'u geri çağırır.

## Paket yapısı

Kök paket `io.pulseboard.core`. Katmanlama `api` -> `application` -> `domain` <- `infrastructure` şeklindedir; `domain` Spring'e bağımlı değildir. ArchUnit testleri hem katmanlamayı hem de kiracı kapsamı kuralını (kiracıya ait veriyi okuyan bir repository metodu kiracı kimliği almalıdır) denetler.

| Katman | İçerik |
|---|---|
| `api` | Alanlara göre REST controller'ları ve DTO'lar: `auth`, `apikey`, `catalog` (servisler ve metrikler), `ingest`, `metrics` (sorgular), `anomaly`, `alert` (kurallar ve geçmiş), `stream` (SSE ve biletler), `internal` (HMAC uç noktası), `ratelimit`, `error` (RFC 9457 problem details), `openapi` |
| `application` | Kullanım senaryoları: `auth`, `apikey`, `catalog`, `ingest`, `query`, `anomaly`, `alert` (değerlendirme, outbox dağıtımı, webhook hedef denetimi ve imzalayıcı), `streamticket`, `ratelimit`, `tenancy`, `common` |
| `domain` | Framework kodu içermeyen varlıklar ve kurallar: `tenancy`, `identity`, `catalog`, `metrics`, `anomaly`, `alert`, `shared` |
| `infrastructure` | Adaptörler: `persistence`, `security` (JWT, API anahtarı ve dahili HMAC filtreleri), `cache`, `ratelimit` (Redis token bucket), `ingest` (JDBC toplu upsert), `stream` (Redis pub/sub'dan SSE'ye), `anomaly`, `alert` (JDBC outbox, Redis bekleme süresi, HTTP gönderici, açılış güvenlik denetimi), `logging` (gizli değer maskeleme), `time` |

## Uç noktalar

Esas kaynak [contracts/api-core.openapi.yaml](../../contracts/api-core.openapi.yaml) dosyasıdır (`make openapi-gen` ile yeniden üretilir; sapma olursa CI başarısız olur). Hatalar `application/problem+json` biçimindedir.

| Metot ve yol | Kimlik doğrulama | Amaç |
|---|---|---|
| `POST /api/v1/auth/register` | yok | Kiracı ve ilk kullanıcıyı oluşturur, token döner |
| `POST /api/v1/auth/login` | yok | `email`, `password`, `tenantSlug` ile giriş |
| `POST /api/v1/auth/refresh` | refresh token | Refresh token'ı döndürür, yeni access token verir |
| `POST /api/v1/auth/logout` | refresh token | Tüm token ailesini iptal eder (idempotent, her zaman 204) |
| `GET`, `POST /api/v1/services` | JWT | Servisleri listeler veya oluşturur |
| `GET /api/v1/services/{serviceId}` | JWT | Tek servisi okur (`lastPointAt` ile) |
| `GET`, `POST /api/v1/services/{serviceId}/metrics` | JWT | Metrik tanımlarını listeler veya oluşturur |
| `GET /api/v1/metrics/{metricId}` | JWT | Tek metrik tanımını okur |
| `GET /api/v1/metrics/{metricId}/points` | JWT | Örneklenmiş seri (en fazla 2000 kova; isteğe bağlı `step`) |
| `POST /api/v1/api-keys` | JWT | API anahtarı oluşturur (düz anahtar yalnızca bir kez döner) |
| `DELETE /api/v1/api-keys/{id}` | JWT | API anahtarını iptal eder |
| `POST /api/v1/ingest` | `X-Api-Key` | En fazla 1.000 nokta alır; `accepted` ve `rejected` ile 202 döner |
| `GET /api/v1/anomalies` | JWT | Filtreli sayfalı liste (metrik, servis, önem derecesi, onay durumu, aralık) |
| `GET /api/v1/anomalies/{id}` | JWT | Tek anomaliyi okur |
| `POST /api/v1/anomalies/{id}/ack` | JWT | Anomaliyi onaylar |
| `POST /api/v1/stream/ticket` | JWT | Tek kullanımlık akış bileti verir (kullanıcı başına dakikada 30) |
| `GET /api/v1/stream/anomalies` | JWT bearer veya `?ticket=` | Yeni anomaliler için Server-Sent Events akışı |
| `GET`, `POST /api/v1/alert-rules` | JWT | Kuralları listeler (her kullanıcı; viewer'lar webhook hedefini yalnızca ana makine adı olarak görür) veya bir kural oluşturur (owner veya admin) |
| `GET`, `PUT`, `DELETE /api/v1/alert-rules/{id}` | JWT | Kuralı okur, değiştirir (düzenlemenin başladığı `version` ile) veya siler; değişiklikler owner ve admin içindir |
| `PATCH /api/v1/alert-rules/{id}` | JWT | Kuralı açar veya kapatır (owner veya admin) |
| `GET /api/v1/alert-events` | JWT | Alarm geçmişi, en yeniden eskiye, sayfalı; kurala, teslim durumuna ve tetiklenme zamanına göre süzülür |
| `GET /api/v1/alert-events/{id}` | JWT | Tek bir alarm olayını içeriğiyle (payload) okur |
| `POST /internal/v1/anomalies/notify` | `X-Internal-Signature` | analytics, anomalileri yazdıktan sonra çağırır; genel API'nin parçası değildir |

## Alarm sistemi

Bir kural, çağıranın kiracısına ait tek bir metriği izler (`THRESHOLD`: operatör ve sayı; `ANOMALY_SEVERITY`: asgari önem derecesi) ve `WEBHOOK` veya `EMAIL_LOG` ile bildirir. Bir kiracı varsayılan olarak en fazla 50 kurala sahip olabilir; adlar kiracı içinde benzersizdir; API anahtarları kural yönetemez.

- **Değerlendirme.** Bir veri alımı grubu işlendikten (commit) sonra, metriklerinin etkin eşik kuralları değerlendirilir (5 saniye önbellek, metrik başına en fazla 20 ve grup başına 200 kural, bir kural grup başına en fazla bir kez tetiklenir, bir saatten eski veya beş dakikadan ileri noktalar yok sayılır). `POST /internal/v1/anomalies/notify`, bildirilen anomalilerin anomali kurallarını değerlendirir.
- **Bekleme süresi ve bütçe.** Eşleşme, Redis'te `alert:cooldown:{ruleId}` anahtarını (`SET NX PX`, kuralın `cooldownSeconds` değeri, 60 ile 86400 arası) ve kiracı başına bir bütçeyi (`ALERT_EVENTS_PER_PERIOD`, saatte 600) talep eder. İkisi de kapalı yönde (fail closed) davranır: Redis kapalıyken olay oluşmaz.
- **Outbox.** Tetiklenme bir `alert_event` satırı ekler (`PENDING`; `(rule_id, source_ref)` benzersiz anahtarı tekrarı etkisiz kılar). Her örnekteki bir yoklayıcı (`ALERT_DISPATCH_INTERVAL`, 5 sn), zamanı gelen satırları `FOR UPDATE SKIP LOCKED` ve 60 saniyelik bir kira (lease) ile alır ve küçük bir havuzda teslim eder. Başarısızlık 10 sn, 60 sn ve 300 sn sonra yeniden denenir (toplam 4 deneme, `ALERT_DISPATCH_MAX_ATTEMPTS`), sonra olay `FAILED` olur; başarı `SENT`'tir. Teslim en az bir kezdir (at least once).
- **Webhook hedef denetimi.** Yalnızca `https`, DNS adı (IP değişmezi yok, kimlik bilgisi yok, `localhost` benzeri ad yok), port `ALERT_WEBHOOK_ALLOWED_PORTS` içinden; ad bir kez çözülür, her adres genel olmalıdır ve bağlantı bu adreslere sabitlenir. Denetim kayıt sırasında ve her teslimden önce yeniden çalışır; yönlendirmeler izlenmez, yanıt gövdesi hiç okunmaz.
- **İmza.** `X-Pulseboard-Event-Id`, `X-Pulseboard-Timestamp` ve `X-Pulseboard-Signature` (`timestamp + "." + gövde` üzerinde, `ALERT_WEBHOOK_SIGNING_SECRET` anahtarlı, küçük harfli onaltılık HMAC-SHA256; en az 32 karakter). Gizli değer boşsa `WEBHOOK` kanalı kapalıdır ve onun için kural reddedilir; Compose gizli değeri zorunlu kılar.
- **Güvensiz anahtarlar.** `ALERT_WEBHOOK_ALLOW_HTTP` ve `ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS` testler ve yerel geliştirme içindir; biri `test` ve `local` profilleri dışında açıksa `AlertSafetyCheck` uygulamayı durdurur.
- **Metrikler.** `pulseboard.alerts.events.created`, `.events.suppressed`, `.deliveries`, `.evaluation.errors` ve `.email` sayaçları yönetim portunda sunulur (Prometheus adları, örneğin `pulseboard_alerts_deliveries_total`).

Karar kaydı [ADR 0005](../../docs/ADR/0005-webhook-ssrf-and-outbox.md), akış [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md#43-alerting), kabul edilen sınırlar [docs/KNOWN_LIMITATIONS.md](../../docs/KNOWN_LIMITATIONS.md) dosyasındadır (İngilizce).

## Güvenlik modeli

- **Kiracılık:** Her istekte JWT'den veya API anahtarından bir `TenantContext` çözülür; kiracı asla istek gövdesinden gelmez.
- **Parolalar:** argon2id. Giriş denemeleri, hash hesaplanmadan önce kiracı slug'ı ve e-posta başına sınırlanır.
- **Token'lar:** HS256 access token'lar (15 dakika). Refresh token'lar (7 gün) bir aile içinde her kullanımda döner; dönmüş bir token'ın yeniden kullanılması aileyi iptal eder ve aile mutlak bir ömürle (`REFRESH_FAMILY_TTL`, varsayılan 30 gün) sona erer.
- **API anahtarları:** SHA-256 hash'i ve 8 karakterlik görünür bir önek olarak saklanır. Başarılı doğrulama Redis'te önbelleğe alınır (`API_KEY_CACHE_TTL`, 60 sn); iptal, önbellekteki sonucun yerine anında geçen bir işaret yazar.
- **Hız sınırları** (Redis token bucket; varsayılanlar bir sonraki bölümde) kimlik doğrulamayı, veri alımını, metrik sorgularını, API anahtarlı istekleri ve başarısız API anahtarı denemelerini kapsar. Redis erişilemezse sınırlayıcı kapalı yönde davranır (API anahtarlı isteklerde 503).
- **İstemci adresi:** `X-Forwarded-For` yalnızca `TRUSTED_PROXIES` içindeki eşlerden kabul edilir (varsayılan: özel Docker aralıkları).
- **Dahili uç nokta:** `X-Internal-Signature` başlığında ham gövde üzerinde HMAC-SHA256, sabit zamanlı karşılaştırma, hash'ten önce gövde sınırı, ayrı güvenlik zinciri. İşleyici, gövdedeki kiracı, metrik ve önem derecesi bilgisini yok sayar ve bildirilen her kimliği veritabanından yeniden okur.
- **Akış:** tek kullanımlık biletler (32 rastgele bayt, Redis'te yalnızca hash, atomik tüketim); kiracı başına 10, örnek başına 500 akış sınırı; en fazla 30 dakika ömür; `ticket=` ve `access_token=` tüm günlük mesajlarında maskelenir.
- **Operasyon:** actuator (`health`, `info`, `prometheus`), Docker ağının dışına açılmayan ayrı bir yönetim portunda (8081) çalışır. İstemcilere asla stack trace veya istisna mesajı döndürülmez.

## Veritabanı migration'ları

Flyway, `src/main/resources/db/migration`. `spring.jpa.hibernate.ddl-auto` değeri `validate`'tir.

| Migration | İçerik |
|---|---|
| `V1__tenancy.sql` | Kiracılar |
| `V2__identity.sql` | Kullanıcılar, refresh token'lar (aileler), API anahtarları |
| `V3__services_and_metrics.sql` | Servisler, metrik tanımları, aylık bölümlenmiş `metric_point` |
| `V4__grants.sql` | `analytics_reader` ve `analytics_writer` rolleri için tablo yetkileri |
| `V5__anomalies.sql` | En az yetkiyle `anomaly` ve `detector_state` |
| `V6__refresh_family_lifetime.sql` | Mutlak oturum ömrü (`family_expires_at`) |
| `V7__refresh_family_expiry_uniform.sql` | Refresh token ailesi başına tek bir bitiş zamanı |
| `V8__alert_rules.sql` | `alert_rule` ve `alert_event` outbox tablosu, indeksleriyle |

Aylık `metric_point` bölümleri önceden oluşturulur (`METRIC_PARTITIONS_MONTHS_AHEAD`, varsayılan 3); bunu hem açılışta hem her gün çalışan bir iş yapar. Veritabanı rolleri Flyway ile değil `infra/postgres/init` ile oluşturulur.

## Yapılandırma

Varsayılanlar `src/main/resources/application.yml` içindedir; Compose ortamı gizli değerleri `.env` dosyasından geçirir.

| Özellik (ortam değişkeni) | Varsayılan | Anlamı |
|---|---|---|
| `JWT_SECRET` | yok, zorunlu | HS256 imza anahtarı, en az 32 bayt |
| `INTERNAL_SHARED_SECRET` | yok, zorunlu | analytics ile paylaşılan HMAC anahtarı, en az 32 karakter |
| `ALERT_WEBHOOK_SIGNING_SECRET` | yok, Compose'ta zorunlu | Alarm webhook'larını imzalar, en az 32 karakter; boşsa WEBHOOK kanalı kapalıdır |
| `ALERT_WEBHOOK_ALLOWED_PORTS` | `443` | Webhook adresinin kullanabileceği portlar |
| `ALERT_WEBHOOK_CONNECT_TIMEOUT`, `ALERT_WEBHOOK_READ_TIMEOUT`, `ALERT_DELIVERY_TIMEOUT` | `5s`, `5s`, `20s` | Webhook zaman aşımları ve tek bir teslimin kesin sınırı |
| `ALERT_MAX_RULES_PER_TENANT`, `ALERT_MAX_RULES_PER_METRIC`, `ALERT_MAX_RULES_PER_BATCH` | `50`, `20`, `200` | Bir kiracının sahip olabileceği kurallar; metrik ve veri alımı grubu başına değerlendirilen kurallar |
| `ALERT_RULE_CACHE_TTL`, `ALERT_MAX_POINT_AGE`, `ALERT_MAX_POINT_FUTURE` | `5s`, `1h`, `5m` | Kural önbellek ömrü; bir noktanın kural tetikleyebileceği, şimdinin etrafındaki pencere |
| `ALERT_EVENTS_PER_PERIOD`, `ALERT_EVENTS_PERIOD` | `600`, `1h` | Bir kiracının oluşturabileceği alarm olayları; fazlası atılır ve sayılır |
| `ALERT_DISPATCHER_ENABLED`, `ALERT_DISPATCH_INTERVAL`, `ALERT_DISPATCH_BATCH_SIZE`, `ALERT_DISPATCH_CONCURRENCY` | `true`, `5s`, `10`, `4` | Outbox yoklayıcısı |
| `ALERT_DISPATCH_LEASE`, `ALERT_DISPATCH_MAX_ATTEMPTS`, `ALERT_DISPATCH_BACKOFF` | `60s`, `4`, `10s,60s,300s` | Talep kirası, olay başına deneme, denemeler arası bekleme |
| `ALERT_HOST_FAILURE_THRESHOLD`, `ALERT_HOST_PAUSE` | `5`, `60s` | Bir alıcıya ardışık kaç başarısızlıktan sonra teslimlerinin duraklayacağı ve ne kadar |
| `REFRESH_FAMILY_TTL` | `30d` | Mutlak oturum ömrü; 7 günlük refresh token'dan kısa olamaz |
| `AUTH_RATE_LIMIT_CLIENT_CAPACITY` / `_PERIOD` | `30` / `1m` | İstemci adresi başına kimlik doğrulama istekleri |
| `AUTH_RATE_LIMIT_ACCOUNT_CAPACITY` / `_PERIOD` | `10` / `15m` | Kiracı slug'ı ve e-posta başına giriş denemeleri |
| `METRIC_QUERY_RATE_LIMIT_CAPACITY` | dakikada `120` | Kiracı başına metrik sorguları |
| `INGEST_RATE_LIMIT_FREE_CAPACITY`, `INGEST_RATE_LIMIT_PRO_CAPACITY` | dakikada `60`, `600` | Plana göre kiracı başına alım grupları |
| `INGEST_MAX_BODY_BYTES` | `524288` | Ham alım gövdesi sınırı |
| `API_KEY_RATE_LIMIT_CLIENT_CAPACITY`, `API_KEY_RATE_LIMIT_INVALID_CAPACITY` | dakikada `1200`, `20` | İstemci adresi başına API anahtarlı istekler ve başarısız denemeler |
| `API_KEY_CACHE_TTL` | `60s` | Doğrulanmış API anahtarlarının Redis önbelleği |
| `METRIC_QUERY_STATEMENT_TIMEOUT` | `5s` | Sorgu başına `statement_timeout`; aşılırsa 503 döner |
| `ANOMALY_STREAM_MAX_PER_TENANT`, `ANOMALY_STREAM_MAX_TOTAL` | `10`, `500` | Eşzamanlı SSE akışları |
| `ANOMALY_STREAM_HEARTBEAT`, `ANOMALY_STREAM_MAX_LIFETIME` | `30s`, `30m` | Canlı tutma aralığı ve en fazla akış ömrü |
| `STREAM_TICKET_TTL`, `STREAM_TICKET_ISSUE_CAPACITY` | `30s`, dakikada `30` | Tek kullanımlık akış biletleri |
| `TRUSTED_PROXIES` | özel Docker aralıkları | `X-Forwarded-For` belirlemesine izin verilen eşlerin regex'i |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX_SIZE` | `localhost`, `5432`, `pulseboard`, `api_core`, boş, `10` | PostgreSQL bağlantısı |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, boş | Redis bağlantısı |
| `SERVER_PORT`, `MANAGEMENT_PORT` | `8080`, `8081` | Uygulama ve actuator portları |

## Testleri ve kapıları çalıştırma

Testler Testcontainers (PostgreSQL ve Redis) kullanır, bu yüzden Docker çalışıyor olmalıdır.

```sh
cd apps/api-core
./gradlew test                                                  # Windows: gradlew.bat test
./gradlew checkstyleMain checkstyleTest test verifyOpenApiDocs  # tam kapı
```

| Görev | Amaç |
|---|---|
| `checkstyleMain`, `checkstyleTest` | Stil kuralları, sıfır uyarıya izin verilir |
| `test` | Birim, entegrasyon, mimari ve saldırı senaryosu testleri; alarm kuralları, outbox teslimi ve webhook hedef denetimleri dahil |
| `generateOpenApiDocs` | `contracts/api-core.openapi.yaml` dosyasını koddan yeniden yazar (`make openapi-gen`) |
| `verifyOpenApiDocs` | Depodaki sözleşme koddan farklıysa başarısız olur |

Depo kök dizininden `make test-api` testleri, `make lint` ise checkstyle'ı çalıştırır.

Docker imajı `Dockerfile` ile oluşturulur; ortam depo kök dizininden `make up` ile başlatılır ([kök README](../../README.tr.md)).
