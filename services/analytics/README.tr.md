# Pulseboard Analytics

[English](README.md) | Türkçe

Anomali tespit servisi (Python 3.14, FastAPI, uv). Zamanlanmış bir iş her 60 saniyede bir, her aktif metriğin son 24 saatini kayan pencereli z-skoru ve Isolation Forest ile puanlar, yeni anomalileri kısıtlı yetkili `analytics_writer` rolüyle kaydeder ve gerçekten eklenen satırları api-core'a bildirir (`POST /internal/v1/anomalies/notify`, HMAC imzalı).
Şema değişiklikleri api-core'un Flyway migration'larında kalır; analytics kendi migration'ı olmadan mevcut tablolardan okur ve onlara yazar.

Servise yalnızca Docker ağının içinden erişilir. Tek dahili uç noktasındaki HMAC imzası dışında kendine ait bir kimlik doğrulaması yoktur.

## Dedektörler

İki dedektör de bir `pandas.DataFrame` üzerinde çalışan saf fonksiyonlardır (girdi şeması için `detectors/base.py`). 24 saatlik penceredeki mevcut noktalar üzerinde çalışırlar; aşağıdaki eşikler örnekleme aralığına bağlı değildir.

| | Kayan pencereli z-skoru (`detectors/zscore.py`) | Isolation Forest (`detectors/isolation_forest.py`) |
|---|---|---|
| Referans | Her noktadan önceki (en fazla) 288 nokta; böylece bir sıçrama kendi referansını bozmaz | Metrik başına eğitilen 24 saatlik pencerenin tamamı |
| Asgari veri | 30'dan fazla nokta (bir nokta, kendinden önce 30 nokta olunca puanlanır) | 60 nokta |
| Özellikler | `value` | `value`, `diff_1`, `rolling_mean_12`, `rolling_std_12`, `hour_of_day_sin`, `hour_of_day_cos` |
| Model | yok | `n_estimators=200`, `contamination=0.02`, `random_state=42` |
| Anomali koşulu | `\|z\| >= 3` | skor `>= 0.65` |
| Skor | `min(1, \|z\| / 6)` | `1 - normalize edilmiş decision_function`, `[0, 1]` aralığında |
| Önem derecesi | LOW `3 <= \|z\| < 4`, MEDIUM `4 <= \|z\| < 5`, HIGH `\|z\| >= 5` | `[0.65, 1.0]` aralığının üçte birlikleri: 0.767 altı LOW, 0.767'den itibaren MEDIUM, 0.883'ten itibaren HIGH |
| Yeniden eğitim | geçerli değil | Her 6 saatte bir veya nokta sayısı en az yüzde 20 arttığında; `trained_at`, `version` ve contamination değeri `detector_state.params` içinde saklanır |
| Düz seri | Referans sabitse anomali üretmez | Değer sütunu düzse anomali üretmez; özellikler standartlaştırılır (medyan ve MAD) ve kırpılır |

Z-skoru, en büyük büyüklüğe yakın bir 2'nin kuvvetine bölünmüş değerler üzerinde çalışır (tam bir işlemdir); böylece çok küçük veya çok büyük birimlerde alt taşma (underflow) olmaz. Isolation Forest'ın yalnızca parametreleri saklanır, eğitilmiş model saklanmaz: yeniden başlatmadan sonra her model bir sonraki çalıştırmada yeniden eğitilir. Kabul edilen sınırlar için (örneğin temiz mevsimsel veride Isolation Forest'ın yanlış pozitifleri) bkz. [docs/KNOWN_LIMITATIONS.md](../../docs/KNOWN_LIMITATIONS.md).

## İş akışı

`jobs/score_all.py`, servis sürecinin içinde APScheduler altında çalışır (`SCHEDULER_ENABLED`; tam olarak tek replika çalıştırın):

1. **Kilit.** Bir Redis anahtarı üzerinde `SET NX PX` (`SCORE_LOCK_TTL_MS`, sahip token'ı, karşılaştır-ve-sil ile bırakma). Kilidin tutulu olduğunu gören çalıştırma atlanır. Kilit bir çalıştırmanın işini sınırlar ama lider seçimi değildir; doğruluk, idempotent eklemelere dayanır.
2. **Seçim.** Son 24 saatte en az bir noktası olan metrikler `analytics_reader` ile okunur. Bir Redis imleci, çalıştırmanın süre sınırında (`SCORE_DEADLINE_SECONDS`) nerede durduğunu hatırlar; böylece sonraki çalıştırma o metrikten sonra devam eder ve her metriğe sıra gelir. Çalıştırma bütçesini tüketen bir metrik geri çekilmeye (backoff) alınır (120 sn'den başlayıp 3600 sn'ye kadar ikiye katlanır) ve zamanı gelince diğerlerinden sonra çalışır.
3. **Puanlama.** Her metrik için son 24 saat yüklenir ve iki dedektör çalışır. Her çalıştırma pencerenin tamamını yeniden puanlar; böylece geç gelen noktalar da yakalanır. Bir metriğin başarısız olması diğerlerini durdurmaz.
4. **Kayıt.** `analytics_writer` ile `INSERT ... ON CONFLICT (metric_id, detector, window_start, window_end) DO NOTHING RETURNING`. Yalnızca gerçekten eklenen satırlar bildirilir; yeniden puanlama asla yinelenen kayıt ya da tekrar bildirim üretmez. `detector_state` upsert edilir.
5. **Bildirim.** Eklenen kimlikler en fazla 500'lük gruplar halinde, HMAC imzalı olarak api-core'a gönderilir; ağ hatalarında, 5xx ve 429 durumlarında üstel geri çekilmeyle üç deneme yapılır. Başarısız bildirim günlüğe yazılır ve kaydedilmiş anomalileri geri almaz; anomaliler `GET /api/v1/anomalies` ile görünür kalır.

Ardından api-core bildirilen her kimliği yeniden okur ve `anomalies:{tenantId}` Redis kanalına yayınlar; oradan panoya Server-Sent Event olarak ulaşır.

## Rol modeli

| Rol | Erişim |
|---|---|
| `analytics_reader` | `metric_point` ve metrik kataloğu üzerinde salt okunur |
| `analytics_writer` | Yalnızca `anomaly` ve `detector_state` ile sınırlı |

Roller `infra/postgres/init` ile oluşturulur; bu betik önce `PUBLIC`'ten her şeyi geri alır, sonra açıkça yetki verir. Tablo yetkileri ise api-core migration'larındadır. Açılışta (`VERIFY_DB_ROLES`, varsayılan `true`) servis şunları denetler: iki rol de `public` şemasında nesne oluşturamaz, her biri tam olarak gereken yetkilere sahiptir, diğer şemalarda hiçbir yetki yoktur ve erişilebilir hiçbir rolde superuser, CREATEROLE, CREATEDB, REPLICATION veya BYPASSRLS bulunmaz. Aksi halde servis başlamayı reddeder.

## Uç noktalar

- `GET /health` - liveness; veritabanı ya da Redis gerektirmez.
- `POST /internal/v1/score` - gövde `{"metricId": "<uuid>"}`; tek bir metriği isteğe bağlı puanlar.
  `X-Internal-Signature` gerektirir: ham gövdenin `INTERNAL_SHARED_SECRET` ile anahtarlanmış, küçük harfli hex HMAC-SHA256 değeri (sabit zamanlı karşılaştırma; gizli anahtar ve imzalar asla günlüğe yazılmaz). Yanıtlar: `401` (geçersiz imza), `404` (bilinmeyen metrik), `413` (gövde 4 KiB üzerinde), `422` (bozuk gövde).

Aynı imza şeması, bu servisin çağırdığı api-core'daki `POST /internal/v1/anomalies/notify` uç noktasını da korur. İmza yalnızca gövdeyi kapsar (zaman damgası yoktur); `notify` idempotent olduğu ve yalnızca veritabanındakini yayınladığı için bu kabul edilebilirdir.

OpenAPI belgesi [`contracts/analytics.openapi.yaml`](../../contracts/analytics.openapi.yaml) olarak depoda tutulur; şu komutla yeniden üretilir:
`uv run python -m pulseboard_analytics.openapi_export ../../contracts/analytics.openapi.yaml`
(belge koddan ayrışırsa bir test başarısız olur).

## Yapılandırma

`settings.py` tarafından ortamdan okunur; geçersiz değerler servisi açılışta durdurur.

| Değişken | Varsayılan | Anlamı |
|---|---|---|
| `DATABASE_READER_URL` | zorunlu | `analytics_reader` DSN'i (metrik kataloğu ve noktalar) |
| `DATABASE_WRITER_URL` | zorunlu | `analytics_writer` DSN'i (anomaliler, dedektör durumu) |
| `REDIS_URL` | zorunlu | İş kilidi, imleç ve geri çekilme durumu için Redis |
| `API_CORE_BASE_URL` | zorunlu | api-core taban URL'i |
| `INTERNAL_SHARED_SECRET` | zorunlu | HMAC anahtarı; boşluklar kırpıldıktan sonra en az 32 karakter, aksi halde servis başlamaz |
| `SCHEDULER_ENABLED` | `true` | Zamanlanmış işi açar veya kapatır |
| `SCORE_INTERVAL_SECONDS` | `60` | İşin çalışma aralığı |
| `SCORE_LOCK_TTL_MS` | `55000` | Bir çalıştırmanın Redis kira süresi |
| `SCORE_DEADLINE_SECONDS` | `50` | Bu süreden sonra yeni metrik başlatılmaz; kilit TTL'inden kısa olmalıdır |
| `SCORE_LEASE_MARGIN_SECONDS` | `5` | Kira süresinden bundan az kaldıysa yeni metrik başlatılmaz |
| `METRIC_LOCK_TIMEOUT_SECONDS` | `10` | Aynı metriği eğiten başka bir çağıranın beklenme süresi |
| `DB_POOL_TIMEOUT_SECONDS`, `DB_CONNECT_TIMEOUT_SECONDS` | `5`, `5` | Boş bağlantı ve yeni bağlantı açma için bekleme süresi |
| `FOREST_CACHE_MAX` | `256` | Bellekte tutulan eğitilmiş forest sayısı |
| `VERIFY_DB_ROLES` | `true` | Reader ve writer yetkilerinin açılış denetimi; `false` yalnızca testler içindir |
| `METRICS_ENABLED`, `METRICS_PORT` | `true`, `9100` | Kendi portunda Prometheus dinleyicisi (ana uygulamada asla yoktur) |

## Metrikler

Servis, Prometheus metriklerini (`pulseboard_analytics_*`: puanlama çalıştırmaları ve süreleri, puanlanan ve başarısız olan metrikler, önem derecesine göre eklenen anomaliler, api-core'a notify çağrıları, model önbelleği ve eğitimleri, veritabanı havuzu beklemeleri, kilit alma) `METRICS_PORT` (9100) üzerindeki ayrı bir dinleyiciden sunar; ana uygulama bu dinleyiciyi hiç bağlamaz. Etiketler sınırlı kümelerdir (sonuçlar, dedektörler, önem dereceleri); metrik kimlikleri, kiracı kimlikleri ve yollar asla etiket olmaz. Varsayılan ortam portu yayınlamaz. İsteğe bağlı Prometheus ve Grafana profili bunu toplar; bkz. [docs/LOAD_TEST.md](../../docs/LOAD_TEST.md) (İngilizce).

Bu servisin yazdığı anomaliler için alarm kuralları, api-core `notify` çağrısını aldıktan sonra değerlendirilir.

## Geliştirme

```sh
uv sync --locked
uv run ruff check .
uv run ruff format --check .
uv run mypy .            # strict
uv run pytest            # PostgreSQL ve Redis konteynerleri için Docker gerekir
```

Depo kök dizininden `make test-analytics` testleri çalıştırır, `make lint` ise ruff ve mypy'ı da içerir. Dedektörler sentetik serilerle (enjekte edilmiş sıçramalar, seviye kaymaları, mevsimsel referanslar), repository'ler ve iş ise gerçek PostgreSQL ve Redis konteynerleriyle test edilir.

```sh
docker build -t pulseboard-analytics .
```

Tüm zincir (alım, puanlama, bildirim, REST ve SSE) `make e2e` ile denetlenir; bkz. [kök README](../../README.tr.md).
