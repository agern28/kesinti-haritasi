# Veri modeli

English: [../en/data-model.md](../en/data-model.md)

Faz 1 keşfinden sonra plan'daki `outage` tablosuna iki şey ekledik: kaynağın kendi id'si (`external_id`) ve koordinat (`lat`, `lon`). İkisi de null olabilir, çünkü her kaynak vermiyor. Tablo Faz 3'te Flyway migration'ı olarak yazılacak, burası onun tarifi.

## outage tablosu

| Kolon | Tip | Null | Açıklama |
|---|---|---|---|
| `id` | uuid | hayır | Birincil anahtar, api tarafında üretilir |
| `source` | varchar(32) | hayır | `BEDAS`, `ISKI`, ... |
| `external_id` | varchar(64) | evet | Kaynağın kendi kayıt id'si. BEDAŞ planlı: `plannedOutage.id`, BEDAŞ arıza: `OUTAGE_NO`. Kaynak id vermiyorsa null |
| `type` | varchar(16) | hayır | `ELECTRICITY`, `WATER`, `GAS` |
| `planned` | boolean | hayır | Planlı mı, arıza mı |
| `il` | varchar(64) | hayır | Normalize edilmiş il adı |
| `ilce` | varchar(64) | hayır | Normalize edilmiş ilçe adı |
| `mahalleler` | text[] | hayır | Normalize edilmiş mahalle listesi, boş dizi olabilir |
| `starts_at` | timestamptz | hayır | Başlangıç |
| `ends_at` | timestamptz | evet | Tahmini bitiş; kaynak vermiyorsa null |
| `reason` | text | evet | Kaynaktaki sebep/açıklama |
| `source_url` | text | hayır | Orijinal duyurunun linki |
| `lat` | double precision | evet | Kaynak koordinat veriyorsa enlem |
| `lon` | double precision | evet | Kaynak koordinat veriyorsa boylam |
| `dedup_key` | varchar(128) | hayır | Tekilleştirme anahtarı, unique |
| `first_seen_at` | timestamptz | hayır | İlk görüldüğü tarama |
| `last_seen_at` | timestamptz | hayır | Son görüldüğü tarama |

`lat` ve `lon` ya ikisi birden dolu ya ikisi birden boş olur (check constraint).

## Tekilleştirme

Aynı kesinti her taramada tekrar geliyor. Hangi satırın hangi kesinti olduğunu `dedup_key` belirliyor:

- `external_id` doluysa: `dedup_key = <source>:<external_id>`, örnek `BEDAS:36876717`.
- `external_id` boşsa: `dedup_key = <source>:h:<sha256>`. Hash'in girdisi: kaynak, normalize ilçe, `starts_at` (UTC, ISO-8601) ve alfabetik sıralanmış normalize mahalle listesi, `|` ile birleştirilmiş.

Neden tek kolon: upsert tek bir unique index üzerinden (`ON CONFLICT (dedup_key)`) yapılıyor, iki farklı yol için iki ayrı upsert yazmıyoruz. Yine de kaynak id'si için ek bir güvence istiyoruz:

```sql
create unique index outage_dedup_key_uq on outage (dedup_key);
create unique index outage_source_external_id_uq on outage (source, external_id)
  where external_id is not null;
```

Hash yolunun bir zayıflığı var: kaynak başlangıç saatini ya da mahalle listesini değiştirirse aynı kesinti yeni bir kayıt gibi görünür, eskisi bir süre sonra `GONE` olur. Id veren kaynaklarda bu sorun yok, id tercih edilmesinin nedeni de bu.

## Collector tarafında değişiklik tespiti

Collector, veritabanına değil Redis Stream'e yazıyor. Her kaydın içeriğinin hash'ini (`content_hash`) tutuyor ve önceki taramayla karşılaştırıyor. Kaydın kimliği yine yukarıdaki `dedup_key` kuralıyla belirleniyor, `content_hash` sadece "değişti mi" sorusuna cevap veriyor:

- yeni `dedup_key` -> `NEW`
- aynı `dedup_key`, farklı `content_hash` -> `UPDATED`
- önceki taramada vardı, bu taramada yok -> `GONE`

## Aktif kesinti

`starts_at <= now()` ve (`ends_at` null ya da `ends_at > now()`). Kaynaktan kaybolan (`GONE`) ama bitiş saati gelmemiş kayıtlar silinmiyor, `last_seen_at` ile takip ediliyor. Harita özetinde nasıl sayılacağı Faz 3'te kararlaştırılacak.

## İndeksler (Faz 3'te)

- `(type, il, ilce)`: filtreli liste ve harita özeti için
- `(starts_at, ends_at)`: aktif kesinti sorgusu için
