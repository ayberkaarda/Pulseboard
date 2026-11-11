# Pulseboard documentation

English | [Türkçe](#türkçe)

## English

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components and responsibilities, domain model, ingestion and detection flows, detector specifications, internal HMAC contract, one-time stream tickets, scheduling details, quality gates |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | Behaviours that are deliberate or accepted for now |
| [SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md) | OWASP ASVS 4.0.3 Level 1 self-assessment: status of every requirement with evidence, and the known gaps |
| [LOAD_TEST.md](LOAD_TEST.md) | k6 load test, its measured results, and the optional Prometheus and Grafana stack (`make up-observability`) |
| [ADR](ADR/README.md) | Architecture decision records: [monorepo](ADR/0001-monorepo.md), [Postgres partitioning](ADR/0002-postgres-partitioning.md), [analytics role separation](ADR/0003-analytics-role-separation.md), [SSE over WebSocket](ADR/0004-sse-over-websocket.md), [webhook SSRF protection and outbox](ADR/0005-webhook-ssrf-and-outbox.md) |
| [images](images) | Screenshots used by the READMEs: overview, anomalies, metric chart, alert rules, rule editor, alert history, Grafana dashboard, mobile, sign in (demo data only) |
| [../contracts](../contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml` and the event JSON Schemas |

Component READMEs:

| Component | English | Türkçe |
|---|---|---|
| Project | [README.md](../README.md) | [README.tr.md](../README.tr.md) |
| api-core | [apps/api-core/README.md](../apps/api-core/README.md) | [apps/api-core/README.tr.md](../apps/api-core/README.tr.md) |
| analytics | [services/analytics/README.md](../services/analytics/README.md) | [services/analytics/README.tr.md](../services/analytics/README.tr.md) |
| web | [apps/web/README.md](../apps/web/README.md) | [apps/web/README.tr.md](../apps/web/README.tr.md) |

The web design system is documented in [apps/web/DESIGN.md](../apps/web/DESIGN.md). The architecture, known-limitations, security checklist, load test and decision record documents are written in English only.

## Türkçe

| Belge | İçerik |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Bileşenler ve sorumlulukları, alan modeli, veri alımı ve tespit akışları, dedektör şartnameleri, dahili HMAC sözleşmesi, tek kullanımlık akış biletleri, zamanlama ayrıntıları, kalite kapıları (İngilizce) |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | Bilerek yapılmış ya da şimdilik kabul edilmiş davranışlar (İngilizce) |
| [SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md) | OWASP ASVS 4.0.3 Seviye 1 öz değerlendirmesi: her gereksinimin durumu, dayanağı ve bilinen eksikler (İngilizce) |
| [LOAD_TEST.md](LOAD_TEST.md) | k6 yük testi, ölçülmüş sonuçları ve isteğe bağlı Prometheus ile Grafana ortamı (`make up-observability`) (İngilizce) |
| [ADR](ADR/README.md) | Mimari karar kayıtları: [monorepo](ADR/0001-monorepo.md), [Postgres bölümlendirme](ADR/0002-postgres-partitioning.md), [analytics rol ayrımı](ADR/0003-analytics-role-separation.md), [WebSocket yerine SSE](ADR/0004-sse-over-websocket.md), [webhook SSRF koruması ve outbox](ADR/0005-webhook-ssrf-and-outbox.md) (İngilizce) |
| [images](images) | README dosyalarında kullanılan ekran görüntüleri: genel bakış, anomaliler, metrik grafiği, alarm kuralları, kural düzenleyicisi, alarm geçmişi, Grafana panosu, mobil, giriş (yalnızca demo verisi) |
| [../contracts](../contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml` ve olayların JSON Schema dosyaları |

Bileşen README dosyaları yukarıdaki tabloda İngilizce ve Türkçe olarak listelenmiştir. Web tasarım sistemi [apps/web/DESIGN.md](../apps/web/DESIGN.md) dosyasındadır. Mimari, bilinen sınırlamalar, güvenlik kontrol listesi, yük testi ve karar kayıtları belgeleri yalnızca İngilizcedir.
