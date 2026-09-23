# Phase 3 - API

Date: 2026-09-13

Türkçe: [../tr/03-api.md](../tr/03-api.md)

The api now reads the events the collector writes to the Redis Stream, stores them in PostgreSQL and serves them over REST and SSE. The moment a new, changed or ended outage lands in the database, an event goes out to connected browsers. With several pods running, every client still gets the event.

## Endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/outages` | Outage list. Filters: `type`, `source`, `il`, `ilce`, `active`; paging `page`, `size` (at most 500). Newest start first. |
| `GET /api/outages/{id}` | A single outage, 404 if missing |
| `GET /api/map/summary` | Active outage counts per district: total, planned/fault, per type. Cached in Redis. |
| `GET /api/sources` | Each source's last successful scan, its feeds and whether it is delayed |
| `GET /api/stream` | SSE: `outage.created`, `outage.updated`, `outage.ended`, `outage.resync` |
| `/swagger-ui.html` | Swagger UI, to try every REST endpoint from the browser |
| `/canli.html` | A small page showing the SSE stream live: connection state, last event id, incoming events |
| `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` | Probes and metrics |

The `il` and `ilce` filters ignore Turkish characters and case: `ilce=sisli`, `ŞİŞLİ` and `Şişli` give the same result.

## UI

At the start of Phase 3 the request was "make sure it has a GUI". The real map UI is Phase 4; to keep the phase order, I read this as a UI for the api itself:
- **Swagger UI** (`/swagger-ui.html`) via springdoc. Endpoint descriptions and parameter examples are in the code.
- **Live event page** (`/canli.html`): a single HTML file inside the api. It connects with `EventSource` and shows the connected / reconnecting state and incoming events in a table. It was needed because Swagger UI can't display an SSE stream, and it is handy for checking the SSE path by hand.

Whether Swagger UI stays enabled in production will be decided in the Helm values in Phase 7 (`springdoc.swagger-ui.enabled`).

## Flow

```
collector -> Redis Stream "outage-events"
   -> OutageStreamConsumer (consumer group "api", one consumer per pod)
   -> OutageEventProcessor: PostgreSQL upsert / gone_at
        -> SummaryCache.refresh(district)
        -> LiveEvents: XADD "sse-events" (event id) + PUBLISH "outage-updates"
   -> every pod: Pub/Sub listener -> SseHub -> browsers connected to that pod
TransitionSweeper (once a minute, one pod): planned outage started/ended by the clock -> same publish path
```

### Stream consumption

- Consumer group `api`. Each pod is one consumer, named after `HOSTNAME`, which is the pod name on Kubernetes. A message goes to exactly one pod.
- The group is created from `0`. When the api starts for the first time it processes everything the collector has written so far.
- A message is acknowledged (XACK) after it is written to the database. If the write fails it isn't acknowledged. A message pending for 60 seconds (the pod may have died) is taken over by another pod (XCLAIM) and retried. On restart a pod first processes the messages still pending under its own name.
- A broken message (missing field, invalid JSON, unknown type) won't get better with retries, so it is acknowledged and skipped, and counted in `api_stream_events_total{result="bad"}`.

### Upsert and deduplication

`INSERT ... ON CONFLICT DO UPDATE ... WHERE` on `dedup_key`:
- A row is updated only if the content hash changed or if the record had disappeared from the source and came back. If the collector writes the same event twice (at-least-once), the database doesn't change and no SSE event goes out.
- If the event's `scannedAt` is older than the row's `last_seen_at`, it isn't applied. An old message that was taken over doesn't overwrite newer data.
- `RETURNING ..., (xmax = 0)` tells whether the row was inserted or updated: `outage.created` or `outage.updated`.
- On GONE the row isn't deleted; `gone_at` is set and `outage.ended` goes out.

### SSE and fan-out across pods

- The pod that processes an event does two things:
  - It writes the event to the `sse-events` stream. The stream id becomes the event id; the last 10,000 events are kept.
  - It sends the same event to the `outage-updates` Pub/Sub channel.
- Every pod listens on the channel and forwards the event to its own connected clients. The processing pod also sends to its own clients through Pub/Sub, not directly, so an event never goes out twice.
- **Last-Event-ID**: when a browser's connection drops, `EventSource` reconnects with the last id it received. The pod reads the missed events from `sse-events` and sends them in order. If the client's last seen event is no longer kept, `outage.resync` goes out and the frontend will reload the list. A `?lastEventId=` parameter is accepted as well as the header.
- Each client keeps the last id it received. Live events that arrive while missed events are being sent wait their turn. No event goes out twice or out of order.
- **Heartbeat**: an SSE comment line (`:hb`) every 15 seconds. Proxies don't cut idle connections, and clients that can't be written to are cleaned up.
- A connection is closed every 30 minutes and the client reconnects with Last-Event-ID. Long-lived connections get refreshed that way.
- An `X-Accel-Buffering: no` header is sent so there is no buffering behind nginx. The frontend's nginx config already has buffering off for `/api/stream`.

### State that changes with time (not in the plan)

The rule "an event goes to the browser the moment an outage ends" can't be met by source events alone. BEDAŞ keeps a planned outage in its list for a day after it has ended. For an outage that ends at 17:00, no event comes from the collector.

For that I added `TransitionSweeper`. Once a minute it publishes `outage.updated` for outages whose start time has come since the last run and `outage.ended` for those whose end time has come, and refreshes those districts' summaries. With several pods, a Redis lock makes sure only one runs it at a time.

### Map summary cache

- The `api:summary` hash (field `IL_KEY|ILCE_KEY`, value JSON) and the `api:summary:ready` flag.
- If the flag is missing (first request, or the 10 minute TTL expired), the summary is rebuilt from the database. When an outage changes, only that district's field is recalculated.
- Hits and misses are counted in `api_summary_cache_total{result}`. The Phase 8 "cache hit" panel will come from here.

### Source status

I made a small addition to the collector: after every scan it writes the feed's status to the `collector:status` hash in Redis (field `SOURCE/feed`, JSON: interval, last successful scan, last item count, last error). The api reads it and groups it by source.

A feed counts as delayed (`stale`) if it has never had a successful scan, or if more than `interval x 2 + 60 s` has passed since its last successful scan. Sources come back in a fixed order; their display names (BEDAŞ, İZSU...) and regions live in the api.

## Database

Flyway migration: `V1__create_outage.sql`. The table follows [data-model.md](data-model.md). **Columns added on top of the plan:**
- `content_hash`: the collector's content hash. This is how we tell whether a repeated event changes anything.
- `gone_at`: the moment the source removed the record from its list. The active outage definition was updated accordingly: `starts_at <= now < ends_at` and `gone_at` empty. Faults disappear from the source when they are fixed, often before the estimated end, and they shouldn't show as active on the map.
- `il_key`, `ilce_key`: a key independent of Turkish characters (the same rule as `Names.key` in the collector). Used in filters and, in Phase 4, for matching the map boundaries.

Data access uses `JdbcClient`, not JPA. The `text[]` column and the `ON CONFLICT ... WHERE ... RETURNING` upsert are awkward in JPA, and I didn't see the need for an ORM for a single table.

## Metrics

On top of the HTTP metrics (`http_server_requests_*`):

| Metric | Meaning |
|---|---|
| `api_stream_events_total{event,result}` | Processed events; result: applied / noop / bad / error |
| `api_sse_clients` | SSE clients connected to this pod |
| `api_sse_events_sent_total` | Events sent to clients |
| `api_sse_delivery_seconds` | Time from writing the event to the database to sending it to the client (histogram) |
| `api_summary_cache_total{result}` | Summary cache hit/miss |

The Phase 8 "connected SSE clients" and "time from database to browser" panels will be drawn from these. The latency is measured on the server up to the moment the event is written to the client; network time isn't included.

Readiness depends on the database and Redis; liveness doesn't. If the database is gone for a moment, the pod stops taking traffic but isn't restarted.

## Tests

The api has 24 tests, all green (`mvn verify`). They all run against real PostgreSQL 18 and Redis 8 (Testcontainers) and share one Spring context. So that tests don't affect each other, each test generates its own province/district/key values.
- **Upsert rules**: new record, same content (no change), changed content, an older event, GONE, a second GONE, a record coming back.
- **Filters**: Turkish characters and case, type, active, paging. Bad requests return 400/404.
- **Stream consumption**:
  - An event written in the collector's format creates a row in the database.
  - The same event arriving again doesn't change the row count.
  - GONE is handled correctly.
  - A broken event is acknowledged and skipped, and no pending message is left behind.
- **SSE**:
  - created and ended events arrive, and so does the heartbeat.
  - With Last-Event-ID the two missed events arrive in order, and an already-seen event doesn't come again.
  - Connecting with a very old id gets `outage.resync`.
  - An outage that has already ended isn't published.
- **Two api instances**: a second application is started in the same JVM. The event is processed by one instance, clients connected to both instances receive it with the same id, and there is a single row in the database.
- **Map summary**: the first request is a miss, later ones are hits. After a new outage and after GONE, the district is updated without rebuilding the cache. A future outage isn't counted.
- **Sources**: last scan, delay, ordering, and a source with no status at all.
- **Time sweep**: an outage whose start time has come gets `outage.updated`, one whose end time has come gets `outage.ended`. While the lock is held, a second run doesn't happen.
- **Other**: health/readiness, Prometheus metrics, `/v3/api-docs`, Swagger UI and `/canli.html`.

The collector has 76 tests. On top of the 73 from Phase 2, the source status writes and the İBB data cleanup are tested.

## Live run (compose)

I ran the whole stack (postgres, redis, collector, api, frontend) against the live sources with `docker compose up --build`. An SSE client was connected through nginx (`localhost:3000/api/stream`) from the start.

- All five containers healthy. The collector's first round:

  | Source | New records |
  |---|---|
  | BEDAŞ planned | 250 |
  | AEDAŞ planned | 231 |
  | ÇEDAŞ planned | 135 |
  | KCETAŞ | 86 |
  | İSKİ | 18,380 |
  | BEDAŞ faults | 3 |
  | AEDAŞ faults | 2 |

- The api processed the stream to the end: 19,087 events, lag 0, no pending messages. The database has 19,087 rows.
- Active outages at that moment: BEDAŞ 18, AEDAŞ 12, ÇEDAŞ 20, KCETAŞ 2. İSKİ had 39 active records, which was a bug (see "Where I got stuck").
- `/api/sources`: all six sources `stale: false`.
- `/api/map/summary`: 38 districts.
- `/api/outages?active=true` through nginx returned 91 records.
- Swagger UI, `/v3/api-docs` and `/canli.html` returned 200.
- The initial load sent 417 `outage.created` events to the browser (with the historical data rule; without it, 18k would have gone out). 9 heartbeats arrived.
- **Latency, with a synthetic event**: from writing NEW to the stream to reaching the browser through nginx took 255 ms, and GONE to `outage.ended` took 249 ms. That includes the test script's 50 ms polling interval. On the server side (`api_sse_delivery`) the average over 423 events was 1.5 ms.
- In the second round one fault was fixed at BEDAŞ and one at AEDAŞ. Both came in as GONE and `outage.ended` went to the browser. Lag and pending were 0 again.
- Memory: api 274 MiB / 512, collector 275 MiB / 384, postgres 69 MiB, redis 25 MiB, frontend 16 MiB.

**Again after the fix (2026-09-14)**: after the İBB parser fix I ran the same check from scratch with an empty database.
- İSKİ: 18,379 records, 0 active. The first run had 39.
- The top of the map summary has no water outages anymore, only electricity (Zeytinburnu 7, Sarıyer 6, Alanya 6). The summary has 60 districts.
- Active outages at that moment: BEDAŞ 66, AEDAŞ 48, KCETAŞ 18, ÇEDAŞ 1. `active=true` through nginx returned 133 records. The numbers differ from the first run because a day later the sources list different outages.
- The api processed the stream to the end: 19,022 events, lag 0, no pending messages. All six sources `stale: false`.
- The initial load sent 349 `outage.created` events to the browser, and 13 heartbeats arrived.
- Latency: NEW to the browser took 228 ms, GONE to `outage.ended` 229 ms (through nginx, including the 50 ms polling). On the server side the average over 351 events was 1.4 ms.
- In the second round one fault was fixed at ÇEDAŞ (GONE), and one new fault each came in at BEDAŞ, AEDAŞ and ÇEDAŞ. Lag and pending were 0 again.
- Memory: api 308 MiB / 512, collector 271 MiB / 384, postgres 94 MiB, redis 30 MiB, frontend 17 MiB.

## Where I got stuck

- **The second api instance connected to the wrong database.** In the two-instance test, the second application tried to connect to a local database. `SpringApplicationBuilder.properties()` sets the lowest-priority defaults, and `application.yml` overrides them. Passing the properties as command-line arguments fixed it.
- **The Turkish-character key broke.** In the filter test, `"ŞİŞLİ".toLowerCase()` with a non-Turkish locale put a separate combining dot (U+0307) after the `i`, which broke the key. `Names.key` now upper-cases with Turkish rules first, then strips marks with Unicode decomposition (NFD).
- **The test client lost an event.** The test SSE client was dropping events it wasn't waiting for. In the time sweep test the events arrived in a different order and the second one got lost. The client now keeps the events that didn't match.
- **Historical data would have flooded the browser.** In the first live run, 18k İSKİ historical records would have gone to the browser as `outage.created`. An outage that has already ended when it first arrives from the source is now stored in the database but not published. The initial load sent 417 events instead of 18k.
- **Fake active outages from İSKİ.** In the same run, 39 İSKİ records showed as "active", and the top of the map summary had 15-16 active water outages in Istanbul. The cause was 39 rows in the 2022-2023 file with an empty end time: an outage without an end was counted as ongoing forever. District names were also abbreviated (G.O.PAŞA, B.ÇEKMECE, K.ÇEKMECE), and the 2022-2023 file has a different schema from 2023-2024. I missed these in Phase 2's normalization. I fixed the İBB parser (details: [02-collector.md](02-collector.md)).

## Found later: active-outage indexes and deep paging (2026-09-17)

The review after Phase 5 added two things (`V2__active_indexes.sql`):

- **Partial indexes.** 18,000 of the 19,000 rows are İSKİ historical data, and records that disappeared from the source (`gone_at` set) count neither in the list nor in the map summary. Two indexes leave those out: `outage_active_starts_idx` (the list: `starts_at desc` ordering and the active filter) and `outage_active_district_idx` (the map summary: province/district, type, planned/fault). The k6 load test in Phase 9 will push these queries.
- **Deep paging is rejected.** If `page * size` goes over 50,000, the api returns 400. Before that, a request like `page=100000` made the database scan a huge offset; on a public API that is a cheap way to put load on it.

## Known limitations

- `last_seen_at` means "last scan in which it changed", not "last scan in which it was seen". The collector only writes changes, so the api doesn't know that an unchanged record is still listed. The plan's line "records that disappeared from the source but haven't reached their end time are tracked with last_seen_at" is covered by `gone_at`.
- At most 1000 missed events are resent and the last 10,000 events are kept. Longer gaps get `outage.resync`.
- `api_sse_clients` is per pod. The total will be summed in Prometheus.
- The summary cache relies on the TransitionSweeper for outages that start or end by the clock. If it doesn't run, the 10 minute TTL fixes things.
