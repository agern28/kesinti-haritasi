# Phase 2 - Collector

Date: 2026-09-13

Türkçe: [../tr/02-collector.md](../tr/02-collector.md)

The collector now scans six sources on their own schedules, turns the data into a common model and writes only changes to a Redis Stream. The source list comes from the Phase 1 decisions ([01-source-survey.md](01-source-survey.md), [phases.md](phases.md)).

## Sources and schedule

| Feed | What | Requests | Interval |
|---|---|---|---|
| `BEDAS/planned` | `GET www.bedas.com.tr/GetItemsData` | 1 | 15 min |
| `BEDAS/unplanned` | `GET kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages` + `GetLocation` for new transformers | 1 + at most 40 | 5 min |
| `AEDAS/planned`, `AEDAS/unplanned` | Same platform, `www.akdenizedas.com.tr` and `/AEDAS/...` | same | 15 / 5 min |
| `CEDAS/planned`, `CEDAS/unplanned` | Same platform, `www.cedas.com.tr` and `/CEDAS/...` | same | 15 / 5 min |
| `KCETAS/planned` | `POST www.kcetas.com.tr/kesinti-sorgu.php`, today + 2 days | 3 | 15 min |
| `IZSU/all` | `GET izsu.gov.tr/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama` | 1 | 5 min |
| `ISKI/daily` | İBB dataset page, the xlsx files only if the links changed | 1 (+2) | 24 h |

- İZSU gives planned work and faults on the same page. By the rule ("if an institution gives both on one page, that page is scanned every 5 minutes") it is one feed scanned every 5 minutes.
- Intervals are set with `collector.feeds.<source>-<feed>.interval`; `enabled: false` turns off a single feed. There is a floor in the code: even if 1 minute is configured, a feed is scanned at most every 5 minutes.
- I use a fixed delay, so the next scan of a feed doesn't start before the previous one has finished.
- On startup the first scans are spread over 0-30 seconds. Each scan also starts with 0-5 seconds of random jitter, so the nine feeds don't all fire in the same second.

## How it works

```
ScanScheduler (one task per feed)
   -> ScanRunner: jitter -> SourceCollector.collect() -> Differ -> StreamPublisher -> save snapshot
SourceCollector -> PoliteHttpClient (robots.txt, per-host ordering and delay, User-Agent)
```

- **`SourceCollector`**: `id()`, `defaultInterval()`, `collect()`. Adding an institution means writing a new class and its fixture test. The three CK Enerji companies share the same classes through a parameter (the `CkCompany` enum).
- **`Outage`**: the common model; its fields match the table in [data-model.md](data-model.md). `dedup_key` and the content hash live in `OutageKeys`.
- **`Differ`**: compares the previous snapshot (`dedup_key -> content hash`) with the new scan. A new key becomes NEW, a changed hash UPDATED, a missing key GONE. If nothing changed the event list stays empty and nothing is written to the stream.
- **Snapshot**: kept in Redis in the hash `collector:snapshot:<source>:<feed>`. It is written to a temporary key first and then `RENAME`d, so no reader ever sees half a snapshot.
- **Stream**: `outage-events`, `MAXLEN ~ 100000`. Fields: `event` (NEW/UPDATED/GONE), `source`, `feed`, `dedupKey`, `contentHash`, `scannedAt`, `payload` (Outage JSON, empty for GONE). The api reads this in Phase 3.
- **Order**: events are written first, then the snapshot is saved. If the collector dies in between, the same events go out again on the next scan (at-least-once). The api will upsert by `dedup_key`, so that's harmless.

## Error isolation

- Every feed is a separate scheduled task. One failing or hanging doesn't affect the others.
- If `collect()` throws, `collector_errors_total` goes up and the snapshot isn't touched. This matters: if İZSU's page layout changes and the table can't be found, the parser throws instead of returning an empty list. Otherwise every record would suddenly become GONE.
- For the same reason an error is raised when KCETAŞ returns `success: false` or `sistem_bakimda: true`, or when no xlsx link is found on the İBB page. An empty list is only treated as normal when the source really has no outages.

## Being gentle with the sources

Every request goes through `PoliteHttpClient`:
- Before every request the host's robots.txt is checked (RFC 9309). 4xx means no restrictions; 5xx or unreachable means fully disallowed. Rules are cached for 24 hours, unreachability for 10 minutes.
- Matching supports `*` and `$`, the longest rule wins, and ties go to Allow.
- Redirects are followed by hand. At every hop the target's robots.txt is checked again; if we are redirected to a disallowed path, the request never goes out.
- Requests to the same host go one at a time, at least 2 seconds apart. If `Crawl-delay` is longer, it is respected: 10 seconds for İBB.
- User-Agent: `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`.
- **CK Enerji transformer locations**: fault rows have no place names, only a transformer number. The transformer -> district/neighbourhood mapping is kept in Redis for 30 days, misses for 1 day. At most 40 new lookups per scan. A fault whose location isn't known yet is skipped in that scan and shows up in later scans.
- **İBB**: once a day only the dataset page is read. The xlsx files are downloaded only if the list of links changed. The CKAN API (`/api/`, disallowed by robots.txt) is never used.

## Common model and identity

| Source | external_id | Note |
|---|---|---|
| BEDAŞ/AEDAŞ/ÇEDAŞ planned | `plannedOutage.id`; `id/DISTRICT` if the record spans several districts | One record per district |
| BEDAŞ/AEDAŞ/ÇEDAŞ faults | `OUTAGE_NO/DISTRICT` | A fault can span several districts and transformer locations are found bit by bit over scans. With the district as part of the id, adding a district later doesn't change the other one. |
| KCETAŞ, İZSU, İSKİ | none | `dedup_key` from the hash (source, district, start, sorted neighbourhoods) |

## Normalization

- **Names**: whitespace collapsed, upper-cased with Turkish rules (i -> İ, ı -> I), and `MAH.`, `MAH`, `MH.`, `MAHALLESİ` removed from the end of neighbourhood names. There is also a key for matching across sources (`Names.key`): it folds Turkish characters to ASCII, so `YUSUFELİ` and `YUSUFELI` give the same key.
- **Date/time**: values without a time zone are treated as Europe/Istanbul. Source formats: `2026-09-10 09:00:00` (CK), ISO with offset (CK faults), ISO without offset (KCETAŞ), `10.09.2026 - 22:00` (İZSU), `12/02/2024 13:30:10` and Excel serial numbers (İBB).
- **Neighbourhoods, BEDAŞ**: `İSTANBUL ESENLER ilce MERKEZ-ORUÇREİS mah ALBAYRAK sk / TURGUT REİS mah ... sk bölgelerinde`. Segments are split on ` / `, the part before `mah` is taken and the `MERKEZ-` prefix is dropped. Empty records like `- mah  sk` are skipped.
- **Neighbourhoods, AEDAŞ/ÇEDAŞ**: `ANTALYA,AKSU,MERKEZ ALTINTAŞ Mah. 31225,...;ANTALYA,MURATPAŞA,...`. `;` separates the district groups. Items marked `Mah.` are treated as reliable. An unmarked candidate that starts with a reliable neighbourhood name is treated as a street remnant and dropped (`MERKEZ DUACI 9035`).
- **Neighbourhoods, ÇEDAŞ free text**: some messages come without the `province,district` prefix (`ÖZÜKAVAK KASABASI, KURTAĞILLI, ...`), and some carry a transformer note (`...KÖYLERİ<TAB>T_TRANSFORMATOR_DAGITIM : ...`). Without a prefix the record goes to the first district, and the transformer note is cut off.
- **Province for faults**: GetLocation only returns district and neighbourhood. For BEDAŞ the province is always İstanbul. For AEDAŞ, the API itself adds a province prefix to names that exist in more than one province, like `BURDUR_MERKEZ`. Without a prefix the district is looked up in the district lists of the company's provinces. AKSU and KEMER exist both in Antalya and in Isparta/Burdur; without a prefix Antalya is assumed.

## Metrics

| Metric | Type | Meaning |
|---|---|---|
| `collector_last_success_timestamp{source,feed}` | gauge | Unix time of the last successful scan |
| `collector_items_total{source,feed}` | counter | Records found by scans (cumulative) |
| `collector_errors_total{source,feed}` | counter | Failed scans |
| `collector_items_last_scan{source,feed}` | gauge | Records found by the last scan |
| `collector_events_total{source,feed,event}` | counter | NEW/UPDATED/GONE written to the stream |
| `collector_scan_duration_seconds{source,feed}` | timer | Scan duration |

**A small deviation from the plan, I need your approval:** the plan defines the metrics with only the `{source}` label. I added a `feed` label next to it. The Phase 8 alert wants a 30 minute threshold for fault pages and 3 hours for planned pages, and that alert can't be written without telling a source's two feeds apart. The `source` label is still there; this only adds a label. The last two metrics are extras for the dashboard.

The series are registered at startup, so they exist before the first scan (`last_success` is 0). The Phase 8 alert has to take that into account.

## Why like this

- **No Apache POI**: to read the İBB file I wrote a 150-line xlsx reader (zip + StAX, only the first sheet and cell text). POI's dependencies and memory use are too much for such a small job on a 4 GB server. DTDs and external entities are off, and entry size is capped.
- **jsoup**: for the İZSU table and the links on the İBB page. Small, no dependencies.
- **Jackson 3**: the version that ships with Spring Boot 4. The app defines its own `JsonMapper`; Instants are written as ISO-8601 strings.
- **Single replica**: the collector has no distributed lock. On Kubernetes it runs with `replicas: 1` (Phase 7). Two copies would send double requests to the sources.

## Tests

73 tests in total, all green (`mvn verify`). None of them go to a live site.
- **Parser tests with fixtures**: BEDAŞ (218 records), AEDAŞ (225 records, 236 outages), ÇEDAŞ (86 records, 102 outages), CK fault and location responses, KCETAŞ (26 features, 24 distinct), İZSU, İBB xlsx (6,410 rows) and the dataset page.
- **Normalization and identity**: names, neighbourhood suffixes, date formats, `dedup_key`, content hash.
- **robots.txt**: with the real files I saw during discovery. `PoliteHttpClient` is tested against a local HTTP server: no request to a disallowed path, no following of a redirect to a disallowed path, no requests at all when robots returns 5xx, delay and Crawl-delay applied, gzip decoded.
- **Diff and scanning**: NEW/UPDATED/GONE, no write without changes, snapshot kept on error, one source's error doesn't affect another, the "source unchanged" path, the schedule floor.
- **With real Redis (Testcontainers)**: the KCETAŞ fixture is scanned twice. The first scan writes 24 NEW, the second leaves the stream length unchanged. When one record changes and one disappears, UPDATED and GONE arrive.
- **The whole application**: health, liveness/readiness (readiness depends on Redis) and per-source metrics on `/actuator/prometheus`.

## Live run (compose)

I ran the collector against the live sources with `docker compose up redis collector` and waited for two rounds (about 9 minutes). The pace was the same as in production; no extra requests.

First round:

| Feed | Records | NEW | Time |
|---|---|---|---|
| BEDAS/planned | 250 | 250 | 6 s |
| BEDAS/unplanned | 1 | 1 | 71 s |
| AEDAS/planned | 231 | 231 | 7 s |
| AEDAS/unplanned | 2 | 2 | 126 s |
| CEDAS/planned | 135 | 135 | 4 s |
| CEDAS/unplanned | 0 | 0 | 10 s |
| KCETAS/planned | 92 (6 duplicates) | 86 | 12 s |
| IZSU/all | 0 | 0 | 9 s |
| ISKI/daily | 25,646 (7,266 duplicates) | 18,380 | 47 s |

- Error count 0 on every feed. robots.txt: İBB and İZSU have files with rules (200), the others have none (404).
- 19,085 events went to the stream. 18,380 of them are İSKİ historical data: the two xlsx files have 25,646 rows in total, and 7,266 rows are shared between the files or repeats of the same record.
- İZSU returned 0 records at that moment. The table was found but had no rows; the relocation work from the fixture has finished. Since the scan didn't fail, we know the table was found.
- The fault feeds took long (AEDAŞ 126 s) because the transformer locations were looked up in the first round. There is a limit of 40 lookups per scan, requests are 2 s apart, and the fault feeds of all three companies share the same host (`kesintiapi.ckenerji.com.tr`) one request at a time. Once the locations were cached, BEDAŞ's second scan took 5 s.

Second round (5 minute feeds):

| Feed | Records | NEW | UPDATED | GONE |
|---|---|---|---|---|
| IZSU/all | 0 | 0 | 0 | 0 |
| CEDAS/unplanned | 0 | 0 | 0 | 0 |
| BEDAS/unplanned | 1 | 0 | 0 | 0 |
| AEDAS/unplanned | 2 | 1 | 1 | 1 |

- The three unchanged feeds wrote nothing to the stream. The stream grew only by AEDAŞ's 3 events (19,085 -> 19,088).
- The AEDAŞ changes are real. Since the locations are cached, a GONE can only happen when a fault disappears from the source. NEW and UPDATED can come from a new fault, or from transformers located in this round; the ones that hit the 40 lookup limit in the first round were looked up in the second.
- Memory: after processing İBB's 25k rows the collector was at 267 MiB / 384 MiB, Redis at 24 MiB. I suggest a 512 Mi limit for the collector in Phase 7. Reading the İBB file as a stream would lower memory, but since it runs once a day I didn't see the need for now.

## Where I got stuck

- AEDAŞ and ÇEDAŞ faults had no province. The first idea was a static district list, but "MERKEZ" exists in three provinces at once. After fetching two GetLocation samples I saw the API returns `BURDUR_MERKEZ` in that case, which mostly solved the problem by itself.
- ÇEDAŞ messages come in three different formats, one of them free text. The parser works on a best-effort basis there. The tests pin the known records; if a new format shows up we'll need to add a fixture and extend the test.
- KCETAŞ returns several transformer rows for the same neighbourhood and time (26 rows, 24 distinct). The Differ deduplicates them.

## Found later: İBB data cleanup (during Phase 3)

In the Phase 3 live run, 39 İSKİ historical records showed as "active" on the map. In Phase 2 I had only looked at the 2023-2024 file; the 2022-2023 file turned out to be different:
- **Different schema**: `ARIZA NUMARASI | ILCE | MAHALLE | ARIZA SEBEP | SORUMLU | BASLANGIC | BITIS | SAAT_FARK | DAKIKA_FARK`. Since the parser finds columns by "header contains", it found the right ones.
- **39 rows with an empty end time**: a record without an end was counted as ongoing. Now, if the end is empty or before the start, it is computed from `SAAT_FARK`/`DAKIKA_FARK`; if that's missing too, the end is taken to be the start. The outage has definitely ended, only its duration is unknown. The 2023-2024 file also had 6 rows with the end before the start.
- **Abbreviated district names** (in both files): `G.O.PAŞA`, `B.ÇEKMECE`, `K.ÇEKMECE` are expanded to their full names. Otherwise they wouldn't match the map boundaries in Phase 4.
- **Placeholder neighbourhoods**: entries like `ADALAR-STANDARTDIŞI ADRES`, meaning the address is unknown, are dropped.
- **Identity**: `ARIZA NUMARASI` isn't unique (6,582 distinct values over 19,236 rows). It isn't used as an id; dedup stays on the hash.

Lesson: I shouldn't have looked at one file of a dataset and assumed all files were the same.

## Known limitations

- On CK, a planned outage in progress shows up both in the planned list and in the fault list (`Bildirimli`). I only take `Bildirimsiz` rows from the faults, so it isn't counted twice. But we don't learn from the fault side when a planned outage actually ended.
- For İZSU, planned vs fault is decided by whether the job name contains "arıza" (fault). A heuristic.
- For sources with hash identity (KCETAŞ, İZSU, İSKİ), a change in start time shows up as NEW + GONE. This was accepted on purpose in the data model.
- The İBB data is historical (2022-2024). The first scan produces thousands of NEW events, but they won't show as active outages on the map. They are collected for the v2.1 neighbourhood report card.
