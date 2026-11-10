# Pulseboard documentation

English | [Türkçe](#türkçe)

## English

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components and responsibilities, domain model, ingestion and detection flows, detector specifications, internal HMAC contract, one-time stream tickets, scheduling details, quality gates |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | Behaviours that are deliberate or accepted for now |
| `ADR` | Architecture decision records (none written yet) |
| [images](images) | Screenshots used by the READMEs (demo data only) |
| [../contracts](../contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml` and the event JSON Schemas |

Component READMEs:

| Component | English | Türkçe |
|---|---|---|
| Project | [README.md](../README.md) | [README.tr.md](../README.tr.md) |
| api-core | [apps/api-core/README.md](../apps/api-core/README.md) | [apps/api-core/README.tr.md](../apps/api-core/README.tr.md) |
| analytics | [services/analytics/README.md](../services/analytics/README.md) | [services/analytics/README.tr.md](../services/analytics/README.tr.md) |
| web | [apps/web/README.md](../apps/web/README.md) | [apps/web/README.tr.md](../apps/web/README.tr.md) |

The web design system is documented in [apps/web/DESIGN.md](../apps/web/DESIGN.md). The architecture and known-limitations documents are written in English only.

## Türkçe

| Belge | İçerik |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Bileşenler ve sorumlulukları, alan modeli, veri alımı ve tespit akışları, dedektör şartnameleri, dahili HMAC sözleşmesi, tek kullanımlık akış biletleri, zamanlama ayrıntıları, kalite kapıları (İngilizce) |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | Bilerek yapılmış ya da şimdilik kabul edilmiş davranışlar (İngilizce) |
| `ADR` | Mimari karar kayıtları (henüz yazılmadı) |
| [images](images) | README dosyalarında kullanılan ekran görüntüleri (yalnızca demo verisi) |
| [../contracts](../contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml` ve olayların JSON Schema dosyaları |

Bileşen README dosyaları yukarıdaki tabloda İngilizce ve Türkçe olarak listelenmiştir. Web tasarım sistemi [apps/web/DESIGN.md](../apps/web/DESIGN.md) dosyasındadır. Mimari ve bilinen sınırlamalar belgeleri yalnızca İngilizcedir.
