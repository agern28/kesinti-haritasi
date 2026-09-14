# Progress

Plan (Turkish): [../plan.md](../plan.md). Turkish version of this file: [../PROGRESS.md](../PROGRESS.md).

Full definition of the phases: [phases.md](phases.md). The items here are a summary; that file is the source of the details.

Common condition for every phase: tests and build green, committed, `docs/tr/NN-...md` and `docs/en/NN-...md` written, this file updated. Work stops at the end of each phase; the next one starts only after approval.

## [x] Phase 1 - Discovery and skeleton
- [x] Outage pages of BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ examined (URL, format, fields, planned/unplanned split, difficulties)
- [x] Sample responses from each source saved as fixtures
- [x] Monorepo skeleton (services/collector, services/api, frontend, helm, gitops, infra, loadtest, docs, .github/workflows)
- [x] docker-compose.yml: postgres, redis, collector, api, frontend
- [x] README.md / README.en.md

Done when: source notes are in docs with a recommendation for hard sources; fixtures live under `services/collector/src/test/resources/fixtures`; `docker compose up` starts five containers and both service health endpoints plus the frontend respond; `mvn verify` is green for both services.

Status: done (2026-09-11). Notes: [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md).
Decisions (2026-09-11): v1 sources are BEDAŞ (electricity) and İSKİ (water, the "Su Kesintileri" file on İBB Open Data). AYEDAŞ is not in v1 (reCAPTCHA). İSKİ's embedded token is not used. Nullable `external_id`, `lat`, `lon` added to the data model ([data-model.md](data-model.md)). No suitable natural gas source was found (İGDAŞ robots.txt, no İGDAŞ outage data on İBB, Başkentgaz doesn't publish, İzmirgaz only has per-street queries).
Later decisions (2026-09-11): İSKİ historical data will be used in the v2.1 neighbourhood report card; İBB is scanned once a day; the scan frequency rule in CLAUDE.md was updated; request drafts for İSKİ, İGDAŞ and Başkentgaz are under `docs/tr/talepler/` (translations in `docs/en/requests/`).
Source survey (21 electricity distribution companies, 10 water utilities): [01-source-survey.md](01-source-survey.md). Approved (2026-09-13): v1 electricity BEDAŞ + AEDAŞ + ÇEDAŞ + KCETAŞ, v1 live water İZSU, ASKİ instead of natural gas in v1.1, the İBB xlsx fixture stays in the repo.

## [x] Phase 2 - Collector
- [x] Common `Outage` model and `SourceCollector` interface
- [x] BEDAŞ (planned: `GetItemsData`, faults: `RetrieveOutages` + transformer location cache) and İSKİ (İBB Open Data XLSX) collectors with fixture-based tests
- [x] robots.txt check before every request (no request if disallowed, Crawl-delay respected)
- [x] AEDAŞ and ÇEDAŞ (same CK Enerji platform as BEDAŞ, same parser), KCETAŞ (one JSON request per date) and İZSU (live water, server-rendered table) collectors
- [x] Date/time and province/district/neighbourhood normalization
- [x] Schedule per source based on how often the source updates, at most every 5 min (unplanned 5 min, planned 15 min, İBB open data once a day), configurable
- [x] Jitter on requests
- [x] Hash-based change detection, NEW / UPDATED / GONE to a Redis Stream
- [x] Prometheus metrics: `collector_last_success_timestamp`, `collector_items_total`, `collector_errors_total`
- [x] A failing source does not stop the others

Done when: fixture parse tests, normalization tests, diff (NEW/UPDATED/GONE) tests and an error isolation test are green; in local compose the collector writes events to the stream and writes nothing on a second scan without changes; `/actuator/prometheus` shows the three metrics with a source label.

Status: done (2026-09-13). Notes: [02-collector.md](02-collector.md). 73 tests green. Two rounds against live sources in compose: 19,085 events in the first round (18,380 of them İSKİ historical data); in the second round unchanged feeds wrote nothing to the stream.
Approved (2026-09-13): a `feed` label on the metrics next to `source` (to separate the 30 min fault / 3 h planned alerts in Phase 8).

## [x] Phase 3 - API
- [x] Stream consumption with a consumer group, upsert by `dedup_key` (`source:external_id` when there is an `external_id`, hash otherwise), Flyway migrations
- [x] `GET /api/outages`, `GET /api/outages/{id}`, `GET /api/map/summary` (Redis cache), `GET /api/sources`, `GET /api/stream` (SSE)
- [x] `outage.created` / `outage.updated` / `outage.ended` events, district summary refreshed in cache
- [x] SSE fan-out across pods through Redis Pub/Sub
- [x] Last-Event-ID and heartbeat
- [x] Separate liveness/readiness, `/actuator/prometheus`
- [x] Testcontainers integration tests
- [x] A browser UI for the API: Swagger UI and a live SSE event page (user request)

Done when: Testcontainers (Postgres + Redis) tests prove that a stream event lands in the database, a repeated event does not create a duplicate, an SSE client receives the event and Pub/Sub fan-out works between two API instances; the collector -> api -> SSE path is checked by hand in compose.

Status: done (2026-09-14). Notes: [03-api.md](03-api.md). 24 api tests and 76 collector tests green. The collector -> api -> SSE path was checked in compose against live sources through nginx: 19,022 events processed, lag 0, a new event reached the browser in about 230 ms. After the İBB data cleanup there are no fake active records from İSKİ.

## [ ] Phase 4 - Frontend
- [ ] React + Vite + Leaflet, openly licensed province/district GeoJSON (license in docs)
- [ ] District colouring, type filter, list on district click
- [ ] Live updates over SSE with a highlight animation
- [ ] Data freshness indicator, last scan per source, delay warning
- [ ] Connection status icon
- [ ] Version/environment label, "What's new" dialog
- [ ] Mobile layout

Done when: `npm run build` and component tests are green; in compose a new outage is highlighted on the map without a reload; stopping the API shows "reconnecting"; usable at mobile width.

## [ ] Phase 5 - Containers and CI
- [ ] Multi-stage Dockerfiles, non-root, small base images, nginx for the frontend
- [ ] One GitHub Actions workflow per service (paths filter, cache, JaCoCo threshold, SonarQube Cloud, Trivy, GHCR + Release on tag)
- [ ] CHANGELOG.md
- [ ] YAPMAN GEREKEN (your part): SonarQube Cloud, SONAR_TOKEN, making GHCR packages public
- [ ] Commands for the v1.0.0 tags

Done when: all three workflows are green on main; the Trivy step fails on CRITICAL; a tag produces an image on GHCR and a Release (first tag is pushed by you).

## [ ] Phase 6 - Infrastructure
- [ ] Terraform: Hetzner CX23, firewall (22 only from your IP, 80/443 open), SSH key, k3s via cloud-init; `terraform.tfvars.example`
- [ ] Steps to fetch the kubeconfig locally
- [ ] cert-manager + Let's Encrypt ClusterIssuer (staging first, then prod)
- [ ] YAPMAN GEREKEN: Hetzner account/token, domain, DNS A record; terraform commands

Done when: `terraform validate` and `terraform plan` are clean; after your apply `kubectl get nodes` is Ready and staging and prod certificates are issued for a test ingress.

## [ ] Phase 7 - Helm and GitOps
- [ ] helm/collector, helm/api, helm/frontend (probes, limits, ConfigMap, secret reference, TLS Ingress, HPA for api)
- [ ] Lightweight PostgreSQL and Redis charts, one database per environment
- [ ] Argo CD, int and prod Applications under gitops/apps, values in gitops/int and gitops/prod
- [ ] CI bumps the INT tag (without fighting branch protection), PROD promotion through a PR
- [ ] int.<domain> and <domain>

Done when: `helm lint` and `helm template` are clean; int and prod are Synced/Healthy in Argo CD; v1.0 is live on `https://<domain>`.

## [ ] Phase 8 - Observability
- [ ] kube-prometheus-stack values that fit into 4 GB
- [ ] ServiceMonitors
- [ ] Grafana dashboards (JSON, provisioned): source health, application, cluster, SSE client count, DB -> browser latency
- [ ] Telegram alert (unplanned 30 min, planned 3 h, daily open data source 26 h)
- [ ] A way to break a source on purpose to test the alert
- [ ] YAPMAN GEREKEN: Telegram bot token and chat id

Done when: dashboards show data; breaking a source sends an alert to Telegram and a resolved message after the fix; node memory stays reasonable.

## [ ] Phase 9 - v1.1 and resilience
- [ ] Natural gas source: ON HOLD. İGDAŞ robots.txt `Disallow: /`, no İGDAŞ outage data on İBB, Başkentgaz doesn't publish outages on its site, İzmirgaz only offers per-street queries (details: [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md)). To be revisited at the start of Phase 9; if a source is found: collector, gas filter and colour, CHANGELOG, automatic to INT, PR to PROD. If not, the new v1.1 source is ASKİ (Ankara live water faults, approved 2026-09-13); it goes through the same pipeline.
- [ ] k6 spike scenario, HPA and cache measurements, results in docs
- [ ] Rollback exercise
- [ ] Demo runbook (TR/EN)

Done when: v1.1 (with the new source) is on PROD; k6 results (req/s, p95, error rate, pod count) are in docs; rollback and roll-forward tried step by step; the runbook covers the demo scenario from the plan command by command.
