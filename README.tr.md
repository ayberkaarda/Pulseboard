<div align="center">

# Pulseboard

**Çok kiracılı (multi-tenant) bir servis sağlığı ve anomali analizi platformu: metrikleri toplar, anomalileri otomatik tespit eder, geldikleri anda canlı gösterir.**

[English](README.md) | Türkçe

[![api-core](https://github.com/ayberkaarda/pulseboard/actions/workflows/api-core.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/api-core.yml)
[![analytics](https://github.com/ayberkaarda/pulseboard/actions/workflows/analytics.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/analytics.yml)
[![web](https://github.com/ayberkaarda/pulseboard/actions/workflows/web.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/web.yml)
[![e2e](https://github.com/ayberkaarda/pulseboard/actions/workflows/e2e.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/e2e.yml)
[![Lisans: MIT](https://img.shields.io/badge/lisans-MIT-blue.svg)](LICENSE)

<img src="docs/images/overview-light.png" alt="Pulseboard genel bakış: her servis için Healthy, Degraded, Down, Stale ve No data durumlarını gösteren kutucuklar" width="900">

</div>

## Özellikler

- **Baştan çok kiracılı.** Her istek, JWT'den veya API anahtarından alınan kiracıya (tenant) bağlanır; kiracıya ait verilerin repository metotları kiracı kimliği olmadan çağrılamaz (bir ArchUnit kuralı bunu denetler).
- **Metrik toplama.** Ajanlar, bir API anahtarıyla `POST /api/v1/ingest` adresine en fazla 1.000 noktalık gruplar gönderir. Yazmalar idempotent toplu upsert işlemleridir ve kiracı başına hız sınırına tabidir.
- **Örneklenmiş (downsampled) metrik sorguları.** Zaman serileri, sabit bir nokta bütçesini aşmayacak şekilde seçilen kovalar (bucket) halinde sunulur; veri aylık bölümlenmiş tabloda tutulur ve her sorgunun bir statement timeout değeri vardır.
- **Anomali tespiti.** Zamanlanmış bir servis, her aktif metriği iki dedektörle puanlar: kayan pencereli z-skoru ve Isolation Forest. Her anomali bir dedektör, bir skor ve bir önem derecesi (LOW, MEDIUM, HIGH) taşır.
- **SSE ile canlı akış.** Yeni anomaliler panoya Server-Sent Events ile iletilir. Tarayıcı, URL'de uzun ömürlü bir token taşımak yerine tek kullanımlık, kısa ömürlü bir bilet (ticket) ile bağlanır.
- **Onaylama (acknowledge).** Anomaliler akıştan veya metrik sayfasından onaylanabilir; arayüz iyimser güncelleme yapar, hata olursa geri alır.
- **Sağlık kutucukları.** Genel bakış, her servisi Healthy, Degraded, Down, Stale (veri eski, açık anomali yok) veya No data olarak gösterir. Durum asla yalnızca renkle anlatılmaz: her durumun kendi simgesi vardır.
- **Alarm kuralları.** Sahipler (owner) ve yöneticiler (admin) metrik başına kural tanımlar: `THRESHOLD` (değer `GT`, `GTE`, `LT` veya `LTE` ile bir sınırı aşar) ya da `ANOMALY_SEVERITY` (bir anomali LOW, MEDIUM veya HIGH düzeyine ulaşır). Bekleme süresi (cooldown, 60 saniye ile 24 saat arası) kuralı tetiklendikten sonra susturur; kiracı başına bir bütçe (varsayılan olarak saatte 600 olay) hacmi sınırlar.
- **Webhook ve günlük teslimi.** Kural `WEBHOOK` (imzalı HTTPS POST, yeniden denemeli) veya `EMAIL_LOG` (tek bir yapılandırılmış günlük satırı; SMTP yoktur) kanalıyla bildirir. Olaylar önce bir veritabanı outbox tablosuna yazılır, bu yüzden yavaş bir alıcı veri alımını geciktirmez; teslim en az bir kez (at least once) olur, yeniden denemeler 10 sn, 60 sn ve 300 sn sonra yapılır.
- **Alarm geçmişi.** Her olay teslim durumu (pending, sent, failed), deneme sayısı, son hata ve içerik (payload) ile listelenir; kurala, duruma ve tarihe göre süzülebilir.
- **SSRF'ye karşı sertleştirilmiş, imzalı webhook'lar.** Hedefler genel (public) `https` DNS adları olmalıdır; çözülen her adres denetlenir ve bağlantı bu adrese sabitlenir. Her istek, zaman damgası ve gövde üzerinde HMAC-SHA256 imzası taşır. Bkz. [Güvenlik özeti](#güvenlik-özeti).
- **Gözlemlenebilirlik (isteğe bağlı).** İsteğe bağlı bir Compose profili Prometheus ve api-core, JVM, alarm sistemi ve analytics puanlama çalışmaları için hazır tanımlı bir Grafana panosu ekler. Bkz. [Gözlemlenebilirlik](#gözlemlenebilirlik-isteğe-bağlı).
- **Güvenlik duruşu.** argon2id, yeniden kullanım tespitli refresh token rotasyonu, özetlenmiş (hash) API anahtarları, hız sınırları, HMAC imzalı dahili uç noktalar, en az yetkili veritabanı rolleri ve sıkı bir Content-Security-Policy. Bkz. [Güvenlik özeti](#güvenlik-özeti).
- **Erişilebilir açık ve koyu tema** (sistem tercihini izler), telefondan masaüstüne duyarlı yerleşim ve her sayfa için axe-core denetimi.

## Mimari

```mermaid
flowchart LR
    Browser["Tarayıcı<br/>Angular SPA"] -->|"HTTP"| Nginx["web<br/>nginx"]
    Agent["Metrik ajanları"] -->|"POST /api/v1/ingest<br/>X-Api-Key"| Nginx
    Nginx -->|"/api/"| API["api-core<br/>Spring Boot"]
    API --> PG[("PostgreSQL 18")]
    API <--> Redis[("Redis 8")]
    AN["analytics<br/>FastAPI"] -->|"analytics_reader (okuma)<br/>analytics_writer (yazma)"| PG
    AN -->|"POST /internal/v1/anomalies/notify<br/>HMAC-SHA256"| API
    AN -.->|"iş kilidi"| Redis
    API -->|"PUBLISH anomalies:tenantId"| Redis
    API -->|"SSE akışı"| Nginx
    API -->|"imzalı HTTPS POST<br/>(yalnızca genel adresler)"| Hook["Webhook alıcıları"]
    Prom["Prometheus + Grafana<br/>(isteğe bağlı profil)"] -.->|"scrape"| API
    Prom -.->|"scrape"| AN
```

1. Ajanlar metrik noktalarını nginx üzerinden **api-core**'a gönderir; api-core API anahtarını doğrular, kiracıya hız sınırı uygular ve aylık bölümlenmiş PostgreSQL tablosuna upsert eder.
2. **analytics** servisi her 60 saniyede bir, her aktif metriğin son 24 saatini puanlar ve yeni anomalileri kısıtlı yetkili bir veritabanı rolüyle yazar.
3. Ardından gerçekten eklenen satırları HMAC imzalı bir dahili çağrıyla api-core'a bildirir. api-core bu kayıtları veritabanından yeniden okur ve kiracıya özel bir Redis kanalına yayınlar.
4. O kiracı için açık SSE akışı bulunan her api-core örneği olayı tarayıcıya iletir.
5. Her veri alımı grubundan sonra ve her anomali bildiriminde api-core eşleşen alarm kurallarını değerlendirir. Tetiklenen kural (bekleme süresi ve kiracı bütçesinden sonra) `alert_event` outbox tablosunda `PENDING` bir satıra dönüşür.
6. Her api-core örneğindeki bir dağıtıcı (dispatcher), zamanı gelen satırları `FOR UPDATE SKIP LOCKED` ile alır, webhook hedefini yeniden doğrular, imzalı isteği gönderir ve olayı `SENT` yapar, yeniden deneme planlar ya da `FAILED` yapar.

Ayrıntılar, sıra diyagramları, alan modeli ve dedektör şartnameleri [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) dosyasındadır (İngilizce).

## Ekran görüntüleri

Aşağıdaki görüntülerin tamamı, demo verisiyle çalışan Docker Compose ortamından alınmıştır (webhook'lar yerel bir alıcıya gitti, bkz. [Alarmlar](#alarmlar)).

### Genel bakış

| Açık | Koyu |
|---|---|
| ![Genel bakış, açık tema](docs/images/overview-light.png) | ![Genel bakış, koyu tema](docs/images/overview-dark.png) |

### Anomaliler

Önem derecesi hem simgeyle hem yazıyla gösterilir; liste en yeniden eskiye sıralıdır, filtreler ve tek tıkla onaylama vardır.

| Açık | Koyu |
|---|---|
| ![Anomaliler, açık tema](docs/images/anomalies-light.png) | ![Anomaliler, koyu tema](docs/images/anomalies-dark.png) |

### Anomali işaretleriyle metrik grafiği

İşaretler yalnızca renkle değil şekille de ayrışır (daire, eşkenar dörtgen, üçgen); sayfa değerleri ayrıca metin ve tablo olarak da verir.

| Açık | Koyu |
|---|---|
| ![Metrik grafiği, açık tema](docs/images/metric-light.png) | ![Metrik grafiği, koyu tema](docs/images/metric-dark.png) |

### Alarm kuralları

Her kural koşulunu, kanalını, bekleme süresini ve son tetiklenmesini gösterir; webhook hedefi yalnızca ana makine adıyla görünür. İzleyiciler (viewer) salt okunur liste görür.

| Açık | Koyu |
|---|---|
| ![Alarm kuralları, açık tema](docs/images/alert-rules-light.png) | ![Alarm kuralları, koyu tema](docs/images/alert-rules-dark.png) |

### Alarm kuralı düzenleyicisi

Webhook adresi tarayıcıda yalnızca ipucu olarak denetlenir (https, IP adresi yok, iç ağ adı yok); asıl kararı sunucu verir.

| Açık | Koyu |
|---|---|
| ![Alarm kuralı düzenleyicisi, açık tema](docs/images/alert-editor-light.png) | ![Alarm kuralı düzenleyicisi, koyu tema](docs/images/alert-editor-dark.png) |

### Alarm geçmişi

Teslim durumu simge ve yazıyla gösterilir. Ayrıntı penceresi içeriği (payload) ve son hatayı gösterir.

| Açık | Koyu |
|---|---|
| ![Alarm geçmişi, açık tema](docs/images/alert-history-light.png) | ![Alarm geçmişi, koyu tema](docs/images/alert-history-dark.png) |

| Olay ayrıntısı, açık | Olay ayrıntısı, koyu |
|---|---|
| ![Alarm olayı ayrıntısı, açık tema](docs/images/alert-event-light.png) | ![Alarm olayı ayrıntısı, koyu tema](docs/images/alert-event-dark.png) |

### Grafana panosu (isteğe bağlı profil)

| Açık | Koyu |
|---|---|
| ![Grafana panosu, açık tema](docs/images/grafana-light.png) | ![Grafana panosu, koyu tema](docs/images/grafana-dark.png) |

### Mobil

| Genel bakış | Anomaliler | Metrik | Alarm geçmişi |
|---|---|---|---|
| <img src="docs/images/mobile-overview-light.png" alt="Mobil genel bakış, açık tema" width="200"> | <img src="docs/images/mobile-anomalies-dark.png" alt="Mobil anomaliler, koyu tema" width="200"> | <img src="docs/images/mobile-metric-light.png" alt="Mobil metrik, açık tema" width="200"> | <img src="docs/images/mobile-alerts-light.png" alt="Mobil alarm geçmişi, açık tema" width="200"> |

### Giriş

| Açık | Koyu |
|---|---|
| ![Giriş, açık tema](docs/images/login-light.png) | ![Giriş, koyu tema](docs/images/login-dark.png) |

## Teknoloji yığını

| Bileşen | Yol | Yığın |
|---|---|---|
| api-core | [`apps/api-core`](apps/api-core) | Java 25, Spring Boot 4.1, Spring Security (JWT), Spring Data JPA, Flyway, Gradle 9.9, Testcontainers, ArchUnit |
| analytics | [`services/analytics`](services/analytics) | Python 3.14, FastAPI, SQLAlchemy 2, APScheduler, NumPy, pandas, scikit-learn, uv |
| web | [`apps/web`](apps/web) | Angular 22 (standalone, signals, zoneless), ECharts, Angular Material, nginx |
| contracts | [`contracts`](contracts) | Her iki servis için OpenAPI 3.1, olaylar için JSON Schema |
| yük testi | [`scripts/loadtest`](scripts/loadtest) | `grafana/k6` imajından çalışan k6 betiği |
| infra | [`infra`](infra) | Docker Compose, PostgreSQL 18, Redis 8; isteğe bağlı Prometheus 3 ve Grafana 12 profili |
| CI | [`.github/workflows`](.github/workflows) | GitHub Actions: bileşen başına bir iş akışı ve gece çalışan bir uçtan uca (e2e) akış |

## Hızlı başlangıç

Pulseboard yerel çalışan bir portföy projesidir ve yalnızca Docker Compose ile çalıştırılır; burada canlıya alma (deployment) yönergesi yoktur.

**Gereksinimler:** Compose v2 destekli Docker. İsteğe bağlı uçtan uca kontrol için ayrıca Python 3. GNU Make kullanışlıdır ama şart değildir.

**1. `.env` dosyasını oluşturun.** Şablonu kopyalayın ve boş olan her değeri doldurun (Compose, boş bir gizli değerle başlamayı reddeder).

```sh
cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
```

Her gizli değeri `openssl rand -hex 24` ile üretin (`INTERNAL_SHARED_SECRET`, `JWT_SECRET` ve `ALERT_WEBHOOK_SIGNING_SECRET` için `openssl rand -hex 32`). OpenSSL yoksa, örneğin Windows'ta:

```sh
py -c "import secrets; print(secrets.token_hex(32))"      # Linux/macOS: python3 -c ...
```

**2. Ortamı başlatın** ve tüm konteynerler sağlıklı olana kadar bekleyin.

```sh
make up
# make olmadan (örneğin GNU Make kurulu olmayan Windows):
docker compose --env-file .env -f infra/docker-compose.yml up -d --build --wait
```

**3. Uygulamayı açın:** <http://localhost:8081> (ya da `.env` içindeki `WEB_PORT`) ve "Create a workspace" bağlantısını seçin.

**4. Durdurun.**

```sh
make down
# veya: docker compose --env-file .env -f infra/docker-compose.yml down        (veri birimlerini de silmek için -v ekleyin)
```

`make up DEV=1`, `infra/docker-compose.dev.yml` dosyasını da ekler (hot reload; Postgres, Redis ve api-core yalnızca `127.0.0.1` üzerinde yayınlanır).
Varsayılan ortamda yalnızca web konteyneri dışarıya açılır: api-core, özel Docker adreslerinden gelen `X-Forwarded-For` başlığına güvenir; ana makineye açılmış bir port, istemcilerin hız sınırı için adreslerini taklit etmesine izin verirdi. Bu yüzden aşağıdaki her şey 8081 numaralı porttaki nginx üzerinden gider.

### Test verisi gönderme

Aşağıdaki kayıt, servis, metrik ve API anahtarı çağrıları [contracts/api-core.openapi.yaml](contracts/api-core.openapi.yaml) dosyasındaki istek şekillerini ve [scripts/e2e/run.py](scripts/e2e/run.py) ile aynı sırayı kullanır. `curl` ve `jq` içeren bir POSIX kabuğu gerekir (Linux, macOS, WSL veya Git Bash).

```sh
BASE=http://localhost:8081

# 1. Bir çalışma alanı kaydedin (parola en az 12 karakter olmalı) ve access token'ı saklayın.
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"change-me-please-123","tenantName":"Acme Demo","tenantSlug":"acme-demo"}' \
  | jq -r .accessToken)

# 2. Bir servis ve bir metrik oluşturun.
SERVICE=$(curl -s -X POST $BASE/api/v1/services -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"checkout","environment":"PROD"}' | jq -r .id)
METRIC=$(curl -s -X POST $BASE/api/v1/services/$SERVICE/metrics -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"latency.p95","unit":"ms","aggregation":"AVG"}' | jq -r .id)

# 3. Bir API anahtarı oluşturun. Anahtarın düz hali yalnızca bir kez döner.
KEY=$(curl -s -X POST $BASE/api/v1/api-keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"demo agent"}' | jq -r .key)

# 4. API anahtarıyla nokta gönderin.
curl -s -X POST $BASE/api/v1/ingest -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{\"source\":\"curl\",\"points\":[{\"metricId\":\"$METRIC\",\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"value\":101.3}]}"
# {"accepted":1,"rejected":[]}
```

Sonraki girişlerde `POST /api/v1/auth/login` çağrısı `email`, `password` ve `tenantSlug` alanlarıyla yapılır.

Dedektörlerin bir şey işaretleyebilmesi için geçmiş veri gerekir (z-skoru en az 31, Isolation Forest en az 60 nokta) ve zamanlayıcı dakikada bir çalışır. Gerçek anomaliler görmek için birkaç yüz noktalık, içinde ani sıçrama bulunan bir seri gönderin; [scripts/e2e/run.py](scripts/e2e/run.py) tam bir örnek içerir (100 civarında 300 nokta ve kısa bir sıçrama).

### Alarmlar

Alarm kuralları uygulamada "Alerts" bölümünden yönetilir (owner ve admin oluşturur; diğer roller okuyabilir) ya da sözleşmedeki `/api/v1/alert-rules` uç noktalarıyla. Bir kural için metrik, koşul ve kanal gerekir. `EMAIL_LOG` hazır çalışır ve alarmı api-core günlüğüne yazar (`docker compose logs api-core`).

`WEBHOOK` kuralı için DNS adı olan genel bir `https` adresi gerekir. Kendi makinenizdeki bir alıcıya ya da bir Compose servisine varsayılan ayarlarla ulaşılamaz; bu, SSRF korumasının amaçlandığı gibi çalışmasıdır, bu yüzden denemeyi genel bir HTTPS uç noktasına karşı yapın. Alıcılar teslimi `X-Pulseboard-Event-Id`, `X-Pulseboard-Timestamp` ve `X-Pulseboard-Signature` başlıklarıyla doğrular (`timestamp + "." + ham gövde` üzerinde, `ALERT_WEBHOOK_SIGNING_SECRET` anahtarlı küçük harfli onaltılık HMAC-SHA256); tarif [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#receiver-verification) dosyasındadır (İngilizce).

`ALERT_WEBHOOK_ALLOW_HTTP` ve `ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS` değişkenlerini ayarlamayın. Yalnızca testler ve yerel geliştirme içindir, api-core bunlarla `test` ve `local` profilleri dışında başlamayı reddeder ve Compose bunları iletmez. Bu README'deki ekran görüntüleri, bu değişkenleri yerel bir alıcı için açan, depo dışındaki geçici bir override dosyasıyla alınmıştır.

### Gözlemlenebilirlik (isteğe bağlı)

```sh
# .env dosyasında ayrıca GRAFANA_ADMIN_PASSWORD gerekir (zorunlu; örneğin openssl rand -hex 16)
make up-observability
# make olmadan:
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.observability.yml --profile observability up -d --build --wait
make down-observability       # tüm projeyi durdurur, birimler (volume) korunur
```

Bu, Prometheus'u (yayınlanan port yok; api-core'u yönetim portundan, analytics'i 9100 portundan toplar) ve <http://127.0.0.1:3000> adresinde Grafana'yı ekler (`GRAFANA_PORT`, yalnızca loopback; kullanıcı `GRAFANA_ADMIN_USER`, varsayılan `admin`). "Pulseboard overview" panosu hazır tanımlıdır. Ayrıntılar ve k6 yük testi (`make loadtest`, ölçülmüş sonuçlarla) [docs/LOAD_TEST.md](docs/LOAD_TEST.md) dosyasındadır (İngilizce).

### Uçtan uca kontrol

`make e2e`, kendi gizli değerleriyle geçici bir Compose projesi (`pulseboard-e2e`) başlatır, sıçramalı gürültülü bir seri gönderir, zamanlayıcıyı bekler ve anomaliyi REST ve SSE üzerinden, kiracı yalıtımını ve dahili HMAC davranışını doğrular. Normal ortamınıza ya da `.env` dosyanıza dokunmaz ve oluşturduğu her şeyi sonunda siler.

```sh
make e2e
# Windows: Makefile, PYTHON değişkenini varsayılan olarak "py" yapar; şu da çalışır:  PYTHON=py make e2e
# make olmadan:  py scripts/e2e/run.py        (Linux/macOS: python3 scripts/e2e/run.py)
# Yarıda kesilmiş bir çalıştırmadan sonra:  make e2e-down
```

## Test ve kalite kapıları

Her bileşenin yüzlerce otomatik testi vardır; api-core ayrıca alarm kurallarını, değerlendirmeyi, outbox teslimini ve webhook hedef denetimlerini kapsar.

| Bileşen | Komut (depo kök dizininden) | Notlar |
|---|---|---|
| tümü | `make test` | sırayla api-core, analytics ve web |
| tümü | `make lint` | checkstyle, ruff + mypy, ESLint |
| api-core | `cd apps/api-core && ./gradlew checkstyleMain checkstyleTest test verifyOpenApiDocs` | Docker gerekir (Testcontainers); kod ile depodaki sözleşme ayrışırsa `verifyOpenApiDocs` başarısız olur |
| analytics | `cd services/analytics && uv sync --locked && uv run ruff check . && uv run ruff format --check . && uv run mypy . && uv run pytest` | Docker gerekir (PostgreSQL ve Redis konteynerleri) |
| web | `cd apps/web && npm run api:check && npm run lint && npm run format:check && npm run typecheck && npm test && npm run build` | Jest; `api:check`, üretilen API tiplerini sözleşmeyle karşılaştırır |
| web (tarayıcı) | `cd apps/web && BASE=http://localhost:8081 node scripts/browser-audit.mjs` | Çalışan ortamda her sayfanın iki temada axe-core taraması; Chrome gerekir, CI'da çalışmaz |
| contracts | `make openapi-gen` | İki OpenAPI belgesini yeniden üretir; sapma olursa CI başarısız olur |
| compose | `make check-compose` | Compose'un ayarlanabilir değerleri servislere ilettiğini, güvensiz webhook anahtarlarının iletilmediğini ve gözlemlenebilirlik profilinin isteğe bağlı olduğunu doğrular |
| yük | `make loadtest` | Çalışan ortama karşı k6 veri alımı ve okuma senaryoları; bkz. [docs/LOAD_TEST.md](docs/LOAD_TEST.md) |

GNU Make olmayan Windows'ta "Komut" sütunundaki komutları doğrudan çalıştırın.

## Güvenlik özeti

- **Parolalar:** argon2id.
- **Oturumlar:** kısa ömürlü JWT access token'lar; refresh token her kullanımda döner, dönmüş bir token'ın yeniden kullanılması tüm token ailesini iptal eder, aile mutlak bir ömürle (varsayılan 30 gün) sona erer ve çıkış (logout) aileyi sunucu tarafında iptal eder.
- **API anahtarları:** yalnızca hash olarak saklanır, Redis'te bir dakika önbelleğe alınır; iptal işaretleri anında etkili olur.
- **Hız sınırları:** kimlik doğrulamada istemci adresi ve hesap başına, metrik alımında ve sorgularında kiracı başına, API anahtarlı isteklerde ve başarısız anahtar denemelerinde adres başına. Redis erişilemezse sistem kapalı yönde (fail closed) davranır.
- **Dahili uç noktalar:** `X-Internal-Signature` (ham gövde üzerinde HMAC-SHA256, sabit zamanlı karşılaştırma, hash'ten önce gövde boyutu sınırı), kendi güvenlik zincirinde; `/internal/` genel proxy tarafından engellenir.
- **Veritabanı rolleri:** analytics salt okunur bir rol ile yalnızca `anomaly` ve `detector_state` tablolarına yazabilen bir rol kullanır; açılışta rollerin tam olarak beklenen yetkilere sahip olduğunu doğrular, değilse başlamaz.
- **Canlı akış:** tek kullanımlık biletler (32 rastgele bayt, 30 saniye, tek kullanım, yalnızca hash olarak saklanır); `ticket=` ve `access_token=` değerleri günlüklerde maskelenir ve proxy sorgu dizelerini kaydetmez.
- **Webhook'lar (SSRF):** yalnızca DNS adı olan `https` adresleri, kimlik bilgisi yok, varsayılan port 443; adın çözüldüğü her adres genel olmalıdır (loopback, özel, link-local ve metadata, carrier-grade NAT ve geçiş aralıkları reddedilir) ve bağlantı denetlenen adrese sabitlenir. Denetim kayıt sırasında ve her teslimde yeniden yapılır. Yönlendirmeler izlenmez, yanıt gövdesi hiç okunmaz. Bkz. [ADR 0005](docs/ADR/0005-webhook-ssrf-and-outbox.md) (İngilizce).
- **İmzalı webhook'lar:** `timestamp + "." + gövde` üzerinde, açılışta zorunlu olan en az 32 karakterlik bir gizli değerle HMAC-SHA256; alıcılar tekrar oynatmaları (replay) reddedebilir ve olay kimliğiyle tekrarları ayıklayabilir.
- **İzleyici maskelemesi:** VIEWER rolündeki kullanıcılar webhook hedeflerini yalnızca ana makine adı olarak görür; kuralları yalnızca owner ve admin oluşturabilir, değiştirebilir veya silebilir.
- **Güvensiz anahtarlar:** `ALERT_WEBHOOK_ALLOW_HTTP` ve `ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS` `test` ve `local` profilleri dışında reddedilir ve Compose tarafından iletilmez.
- **Tarayıcı:** yanıt başına nonce'lu, satır içi script içermeyen sıkı bir Content-Security-Policy, yalnızca bellekte tutulan access token ve güvenli `returnUrl` işleme.

OWASP ASVS 4.0.3 Seviye 1'e karşı bir öz değerlendirme [docs/SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md) dosyasındadır; kabul edilen ödünleşimler [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) dosyasında listelenmiştir (ikisi de İngilizce).

## Proje durumu

| Faz | Kapsam | Durum |
|---|---|---|
| P0 | Temel: monorepo, sabitlenmiş araç sürümleri, Compose, CI, Makefile | Tamamlandı |
| P1 | Çekirdek alan ve kimlik doğrulama: şema, kiracılık, kayıt/giriş/yenileme, API anahtarları | Tamamlandı |
| P2 | Veri alımı ve sorgular: toplu alım, hız sınırları, örneklenmiş metrik sorguları, OpenAPI | Tamamlandı |
| P3 | Analytics servisi: dedektörler, zamanlayıcı, dahili bildirim, SSE yayın yolu | Tamamlandı |
| P4 | Pano: kimlik doğrulama akışları, genel bakış, metrik grafikleri, canlı anomali akışı, onaylama | Tamamlandı |
| P5 | Alarm sistemi: kurallar, değerlendirme, webhook teslimi, geçmiş | Tamamlandı |
| P6 | Sağlamlaştırma: gözlemlenebilirlik, yük testi, güvenlik kontrol listesi, son belgeler ve ADR'ler | Tamamlandı |

Planlanan tüm fazlar tamamlandı. Kalan eksikler (örneğin SMTP yok, veri saklama işi yok, Compose ortamında TLS yok) [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) ve [docs/SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md) dosyalarında listelenmiştir.

## Proje yapısı

```text
pulseboard/
├── apps/
│   ├── api-core/          Spring Boot servisi: kimlik, kiracılık, veri alımı, sorgular, anomaliler, SSE, alarm sistemi
│   └── web/               nginx ile sunulan Angular 22 panosu
├── services/
│   └── analytics/         FastAPI servisi: dedektörler, zamanlayıcı, anomali yazımı
├── contracts/             OpenAPI belgeleri ve olayların JSON Schema dosyaları
├── infra/                 Docker Compose dosyaları, PostgreSQL rol kurulumu, Prometheus ve Grafana yapılandırması
├── scripts/               Uçtan uca kontrol, Compose ayar kontrolü, k6 yük testi
├── docs/                  Mimari, bilinen sınırlamalar, güvenlik kontrol listesi, yük testi, ADR'ler, ekran görüntüleri
├── .github/workflows/     CI: api-core, analytics, web, e2e
├── Makefile
└── .env.example
```

## Dokümantasyon

| Belge | İçerik |
|---|---|
| [docs/README.md](docs/README.md) | Dokümantasyon dizini |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Bileşenler, alan modeli, temel akışlar, dedektör şartnameleri, dahili sözleşmeler (İngilizce) |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Kabul edilen ödünleşimler (İngilizce) |
| [docs/SECURITY_CHECKLIST.md](docs/SECURITY_CHECKLIST.md) | Kanıtları ve bilinen eksikleriyle OWASP ASVS 4.0.3 Seviye 1 öz değerlendirmesi (İngilizce) |
| [docs/LOAD_TEST.md](docs/LOAD_TEST.md) | Ölçülmüş sonuçlu k6 yük testi ve isteğe bağlı Prometheus ile Grafana ortamı (İngilizce) |
| [docs/ADR](docs/ADR/README.md) | Mimari karar kayıtları: monorepo, bölümlendirme, analytics rol ayrımı, SSE, webhook SSRF koruması ve outbox (İngilizce) |
| [contracts](contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml`, olay JSON Schema dosyaları |
| [apps/api-core/README.tr.md](apps/api-core/README.tr.md) | Backend'e genel bakış, uç noktalar, alarm sistemi, ayarlar |
| [services/analytics/README.tr.md](services/analytics/README.tr.md) | Dedektörler, iş akışı, ayarlar |
| [apps/web/README.tr.md](apps/web/README.tr.md) | Frontend'e genel bakış; tasarım sistemi [apps/web/DESIGN.md](apps/web/DESIGN.md) dosyasında |

## Yapılandırma

`.env` dosyasında ayarlanır ([.env.example](.env.example) dosyasına bakın); aşağıdaki gizli değerlerin hepsi zorunludur ve boş olamaz.

| Değişken | Amaç |
|---|---|
| `POSTGRES_PASSWORD`, `API_CORE_DB_PASSWORD`, `ANALYTICS_READER_DB_PASSWORD`, `ANALYTICS_WRITER_DB_PASSWORD` | Veritabanı yönetici ve rol başına parolalar |
| `REDIS_PASSWORD` | Redis `requirepass`; api-core ve analytics kullanır (yalnızca URL'de güvenli karakterler) |
| `INTERNAL_SHARED_SECRET` | api-core / analytics dahili çağrıları için HMAC anahtarı, en az 32 karakter |
| `JWT_SECRET` | JWT imza anahtarı, en az 32 bayt |
| `ALERT_WEBHOOK_SIGNING_SECRET` | Alarm webhook'larını imzalayan HMAC anahtarı (`X-Pulseboard-Signature`), en az 32 karakter; api-core daha kısasıyla başlamayı reddeder |
| `WEB_PORT` | Web giriş noktasının ana makine portu (varsayılan 8081) |
| `GRAFANA_ADMIN_PASSWORD` | Yalnızca `make up-observability` için: Grafana yönetici parolası (orada zorunlu, varsayılanı yok) |
| `GRAFANA_ADMIN_USER`, `GRAFANA_PORT` | İsteğe bağlı: Grafana yönetici kullanıcısı (varsayılan `admin`) ve loopback portu (varsayılan 3000) |

İsteğe bağlı alarm ayarları (hepsinin varsayılanı vardır ve Compose tarafından iletilir): `ALERT_WEBHOOK_ALLOWED_PORTS` (443), `ALERT_MAX_RULES_PER_TENANT` (50), `ALERT_DISPATCHER_ENABLED` (true), `ALERT_DISPATCH_INTERVAL` (5s), `ALERT_DISPATCH_MAX_ATTEMPTS` (4), `ALERT_EVENTS_PER_PERIOD` (600) ve `ALERT_EVENTS_PERIOD` (1h). Ayarlanabilir sınırlar ve zamanlayıcı ayarları `.env.example` dosyasında ve bileşen README'lerinde açıklanmıştır.

## Katkı

Bu, kişisel bir portföy projesidir ve şu an dışarıdan katkıya açık değildir; issue ve öneriler memnuniyetle karşılanır. Değişiklikler conventional commit biçimini izler (`feat`, `fix`, `docs`, `test`, `build`). Depodaki bir OpenAPI sözleşmesi kodun ürettiğinden farklıysa CI başarısız olur; bu durumda `make openapi-gen` ile yeniden üretin.

## Lisans

[MIT Lisansı](LICENSE) ile yayımlanmıştır. Telif hakkı (c) 2026 Ayberk Arda.
