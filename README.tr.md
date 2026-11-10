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
```

1. Ajanlar metrik noktalarını nginx üzerinden **api-core**'a gönderir; api-core API anahtarını doğrular, kiracıya hız sınırı uygular ve aylık bölümlenmiş PostgreSQL tablosuna upsert eder.
2. **analytics** servisi her 60 saniyede bir, her aktif metriğin son 24 saatini puanlar ve yeni anomalileri kısıtlı yetkili bir veritabanı rolüyle yazar.
3. Ardından gerçekten eklenen satırları HMAC imzalı bir dahili çağrıyla api-core'a bildirir. api-core bu kayıtları veritabanından yeniden okur ve kiracıya özel bir Redis kanalına yayınlar.
4. O kiracı için açık SSE akışı bulunan her api-core örneği olayı tarayıcıya iletir.

Ayrıntılar, sıra diyagramları, alan modeli ve dedektör şartnameleri [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) dosyasındadır (İngilizce).

## Ekran görüntüleri

Aşağıdaki görüntülerin tamamı, demo verisiyle çalışan Docker Compose ortamından alınmıştır.

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

### Mobil

| Genel bakış | Anomaliler | Metrik |
|---|---|---|
| <img src="docs/images/mobile-overview-light.png" alt="Mobil genel bakış, açık tema" width="260"> | <img src="docs/images/mobile-anomalies-dark.png" alt="Mobil anomaliler, koyu tema" width="260"> | <img src="docs/images/mobile-metric-light.png" alt="Mobil metrik, açık tema" width="260"> |

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
| infra | [`infra`](infra) | Docker Compose, PostgreSQL 18, Redis 8 |
| CI | [`.github/workflows`](.github/workflows) | GitHub Actions: bileşen başına bir iş akışı ve gece çalışan bir uçtan uca (e2e) akış |

## Hızlı başlangıç

Pulseboard yerel çalışan bir portföy projesidir ve yalnızca Docker Compose ile çalıştırılır; burada canlıya alma (deployment) yönergesi yoktur.

**Gereksinimler:** Compose v2 destekli Docker. İsteğe bağlı uçtan uca kontrol için ayrıca Python 3. GNU Make kullanışlıdır ama şart değildir.

**1. `.env` dosyasını oluşturun.** Şablonu kopyalayın ve boş olan her değeri doldurun (Compose, boş bir gizli değerle başlamayı reddeder).

```sh
cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
```

Her gizli değeri `openssl rand -hex 24` ile üretin (`INTERNAL_SHARED_SECRET` ve `JWT_SECRET` için `openssl rand -hex 32`). OpenSSL yoksa, örneğin Windows'ta:

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

### Uçtan uca kontrol

`make e2e`, kendi gizli değerleriyle geçici bir Compose projesi (`pulseboard-e2e`) başlatır, sıçramalı gürültülü bir seri gönderir, zamanlayıcıyı bekler ve anomaliyi REST ve SSE üzerinden, kiracı yalıtımını ve dahili HMAC davranışını doğrular. Normal ortamınıza ya da `.env` dosyanıza dokunmaz ve oluşturduğu her şeyi sonunda siler.

```sh
make e2e
# Windows: Makefile, PYTHON değişkenini varsayılan olarak "py" yapar; şu da çalışır:  PYTHON=py make e2e
# make olmadan:  py scripts/e2e/run.py        (Linux/macOS: python3 scripts/e2e/run.py)
# Yarıda kesilmiş bir çalıştırmadan sonra:  make e2e-down
```

## Test ve kalite kapıları

Her bileşenin yüzlerce otomatik testi vardır (bu yazı hazırlanırken: api-core 374, analytics 326, web 374).

| Bileşen | Komut (depo kök dizininden) | Notlar |
|---|---|---|
| tümü | `make test` | sırayla api-core, analytics ve web |
| tümü | `make lint` | checkstyle, ruff + mypy, ESLint |
| api-core | `cd apps/api-core && ./gradlew checkstyleMain checkstyleTest test verifyOpenApiDocs` | Docker gerekir (Testcontainers); kod ile depodaki sözleşme ayrışırsa `verifyOpenApiDocs` başarısız olur |
| analytics | `cd services/analytics && uv sync --locked && uv run ruff check . && uv run ruff format --check . && uv run mypy . && uv run pytest` | Docker gerekir (PostgreSQL ve Redis konteynerleri) |
| web | `cd apps/web && npm run api:check && npm run lint && npm run format:check && npm run typecheck && npm test && npm run build` | Jest; `api:check`, üretilen API tiplerini sözleşmeyle karşılaştırır |
| web (tarayıcı) | `cd apps/web && BASE=http://localhost:8081 node scripts/browser-audit.mjs` | Çalışan ortamda her sayfanın iki temada axe-core taraması; Chrome gerekir, CI'da çalışmaz |
| contracts | `make openapi-gen` | İki OpenAPI belgesini yeniden üretir; sapma olursa CI başarısız olur |
| compose | `make check-compose` | Compose'un ayarlanabilir değerleri servislere ilettiğini doğrular |

GNU Make olmayan Windows'ta "Komut" sütunundaki komutları doğrudan çalıştırın.

## Güvenlik özeti

- **Parolalar:** argon2id.
- **Oturumlar:** kısa ömürlü JWT access token'lar; refresh token her kullanımda döner, dönmüş bir token'ın yeniden kullanılması tüm token ailesini iptal eder, aile mutlak bir ömürle (varsayılan 30 gün) sona erer ve çıkış (logout) aileyi sunucu tarafında iptal eder.
- **API anahtarları:** yalnızca hash olarak saklanır, Redis'te bir dakika önbelleğe alınır; iptal işaretleri anında etkili olur.
- **Hız sınırları:** kimlik doğrulamada istemci adresi ve hesap başına, metrik alımında ve sorgularında kiracı başına, API anahtarlı isteklerde ve başarısız anahtar denemelerinde adres başına. Redis erişilemezse sistem kapalı yönde (fail closed) davranır.
- **Dahili uç noktalar:** `X-Internal-Signature` (ham gövde üzerinde HMAC-SHA256, sabit zamanlı karşılaştırma, hash'ten önce gövde boyutu sınırı), kendi güvenlik zincirinde; `/internal/` genel proxy tarafından engellenir.
- **Veritabanı rolleri:** analytics salt okunur bir rol ile yalnızca `anomaly` ve `detector_state` tablolarına yazabilen bir rol kullanır; açılışta rollerin tam olarak beklenen yetkilere sahip olduğunu doğrular, değilse başlamaz.
- **Canlı akış:** tek kullanımlık biletler (32 rastgele bayt, 30 saniye, tek kullanım, yalnızca hash olarak saklanır); `ticket=` ve `access_token=` değerleri günlüklerde maskelenir ve proxy sorgu dizelerini kaydetmez.
- **Tarayıcı:** yanıt başına nonce'lu, satır içi script içermeyen sıkı bir Content-Security-Policy, yalnızca bellekte tutulan access token ve güvenli `returnUrl` işleme.

Kabul edilen ödünleşimler [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) dosyasında listelenmiştir (İngilizce).

## Proje durumu

| Faz | Kapsam | Durum |
|---|---|---|
| P0 | Temel: monorepo, sabitlenmiş araç sürümleri, Compose, CI, Makefile | Tamamlandı |
| P1 | Çekirdek alan ve kimlik doğrulama: şema, kiracılık, kayıt/giriş/yenileme, API anahtarları | Tamamlandı |
| P2 | Veri alımı ve sorgular: toplu alım, hız sınırları, örneklenmiş metrik sorguları, OpenAPI | Tamamlandı |
| P3 | Analytics servisi: dedektörler, zamanlayıcı, dahili bildirim, SSE yayın yolu | Tamamlandı |
| P4 | Pano: kimlik doğrulama akışları, genel bakış, metrik grafikleri, canlı anomali akışı, onaylama | Tamamlandı |
| P5 | Alarm sistemi: kurallar, değerlendirme, webhook teslimi, geçmiş | Planlandı |
| P6 | Sağlamlaştırma: gözlemlenebilirlik, yük testi, güvenlik kontrol listesi, son belgeler ve ADR'ler | Planlandı |

Alarm sistemi henüz geliştirilmedi. `docs/ARCHITECTURE.md` bunun hedeflenen tasarımını anlatır; mevcut API ve arayüzde alarm kuralı yoktur.

## Proje yapısı

```text
pulseboard/
├── apps/
│   ├── api-core/          Spring Boot servisi: kimlik, kiracılık, veri alımı, sorgular, anomaliler, SSE
│   └── web/               nginx ile sunulan Angular 22 panosu
├── services/
│   └── analytics/         FastAPI servisi: dedektörler, zamanlayıcı, anomali yazımı
├── contracts/             OpenAPI belgeleri ve olayların JSON Schema dosyaları
├── infra/                 Docker Compose dosyaları ve PostgreSQL rol kurulumu
├── scripts/               Uçtan uca kontrol ve Compose ayar kontrolü
├── docs/                  Mimari, bilinen sınırlamalar, ekran görüntüleri
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
| `docs/ADR` | Mimari karar kayıtları (klasör şimdilik boş) |
| [contracts](contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml`, olay JSON Schema dosyaları |
| [apps/api-core/README.tr.md](apps/api-core/README.tr.md) | Backend'e genel bakış |
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
| `WEB_PORT` | Web giriş noktasının ana makine portu (varsayılan 8081) |

Ayarlanabilir sınırlar ve zamanlayıcı ayarları `.env.example` dosyasında ve bileşen README'lerinde açıklanmıştır.

## Katkı

Bu, kişisel bir portföy projesidir ve şu an dışarıdan katkıya açık değildir; issue ve öneriler memnuniyetle karşılanır. Değişiklikler conventional commit biçimini izler (`feat`, `fix`, `docs`, `test`, `build`). Depodaki bir OpenAPI sözleşmesi kodun ürettiğinden farklıysa CI başarısız olur; bu durumda `make openapi-gen` ile yeniden üretin.

## Lisans

[MIT Lisansı](LICENSE) ile yayımlanmıştır. Telif hakkı (c) 2026 Ayberk Arda.
