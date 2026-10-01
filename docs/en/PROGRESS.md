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

## [x] Phase 4 - Frontend
- [x] React + Vite + Leaflet, openly licensed province/district GeoJSON (license in docs)
- [x] District colouring, type filter, list on district click
- [x] Live updates over SSE with a highlight animation
- [x] Data freshness indicator, last scan per source, delay warning
- [x] Connection status icon
- [x] Version/environment label, "What's new" dialog
- [x] Mobile layout

Done when: `npm run build` and component tests are green; in compose a new outage is highlighted on the map without a reload; stopping the API shows "reconnecting"; usable at mobile width.

Status: done (2026-09-14). Notes: [04-frontend.md](04-frontend.md). 34 frontend tests and the build are green. Checked in compose against live sources with headless Chromium: a new outage was highlighted on the map within half a second without a reload, stopping the api showed "Yeniden bağlanıyor" (reconnecting), no horizontal scroll at 390 px. District boundaries from OCHA HDX COD-AB (HGK data, CC BY-IGO), as TopoJSON. This phase also fixed the KCETAŞ province bug in the collector (Gemerek is in Sivas); 78 collector tests green. CHANGELOG.md started in this phase for the What's new window.

## [x] Phase 5 - Containers and CI
- [x] Multi-stage Dockerfiles, non-root, small base images, nginx for the frontend
- [x] One GitHub Actions workflow per service (paths filter, cache, JaCoCo threshold, SonarQube Cloud, Trivy, GHCR + Release on tag)
- [x] CHANGELOG.md
- [x] YAPMAN GEREKEN (your part): SonarQube Cloud, SONAR_TOKEN, making GHCR packages public
- [x] Commands for the v1.0.0 tags

Done when: all three workflows are green on main; the Trivy step fails on CRITICAL; a tag produces an image on GHCR and a Release (first tag is pushed by you).

Status: done (2026-09-15). Notes: [05-container-and-ci.md](05-container-and-ci.md). Everything is green locally: `mvn verify` with the JaCoCo floor in both services (85%; measured 91% and 92%), frontend tests with the coverage floor, all three images, `actionlint`. On the first local scan Trivy failed on three CRITICAL vulnerabilities in Tomcat 11.0.24; clean after pinning 11.0.25. On GitHub (2026-09-15, `main`): the collector, api and frontend workflows are green (tests, coverage floor, image, Trivy); `compose-smoke` failed on its first run because of `--ip` and is green after the fix. SonarQube Cloud is set up and `SONAR_TOKEN` added: red on the first analysis because the gate couldn't be computed, `OK` in all three projects from the second analysis on. The 7 findings of the first analysis (2 of them a false alarm about dynamic SQL, 5 small bugs) were fixed. v1.0.0: the first tags failed at the Sonar step (SonarCloud treats a tag as a separate branch); once Sonar was skipped on tag runs, the tags were moved to `11f3377`. All three tag runs are green, the images are on GHCR (`1.0.0`, `latest`, public), and the three GitHub Releases took their notes from the CHANGELOG with the SBOM attached.

## [x] Phase 6 - Infrastructure (local)
- [x] Local k3s cluster: `infra/k3d/cluster.yaml` (1 server + 1 agent, k3s v1.35.5 pinned, 80/443 bound to localhost, metrics-server enabled)
- [x] Terraform (`infra/terraform/local`): `kesinti-int` and `kesinti-prod` namespaces, cert-manager v1.21.2, the issuer chart; `terraform.tfvars.example`
- [x] cert-manager + a ClusterIssuer backed by our own CA (`helm/cluster-issuers`, switches to ACME with `letsencrypt.enabled`)
- [x] Make targets: `cluster-up`, `bootstrap`, `cluster-status`, `cluster-down`
- [x] A fifth workflow: `infra.yml` (terraform fmt/validate, helm lint, the issuer chart rendered in both modes, `cluster.yaml` YAML check)
- [ ] The Hetzner path (hcloud provider, CX23, firewall, cloud-init, Let's Encrypt): postponed, the work it needs is at the end of [06-infrastructure.md](06-infrastructure.md)
- Nothing needed from you: no account, token, domain or DNS

Status: done (2026-10-02). Notes: [06-infrastructure.md](06-infrastructure.md). `terraform validate` and `terraform fmt` are clean, the plan is 4 resources, the apply took 40 seconds. `kubectl get nodes`: both nodes Ready. `kubectl get clusterissuers`: `selfsigned-bootstrap` and `kesinti-ca` True, message "Signing CA verified". A test Certificate for `int.kesinti.localhost` was ready in two seconds with `issuer=CN=Kesinti Haritasi Lokal CA` and 90 days validity, then deleted (the real Ingress certificates come in Phase 7). Traefik answers on 80 and 443 (404, since there is no Ingress). Windows resolves `kesinti.localhost` to ::1 on its own. The cluster plus the compose stack use 2.3 GB of RAM. Where I got stuck: `sudo` asks for a password, so k3d went to `~/.local/bin`; k3d v5.9.0 publishes no separate sha256 file; the cert-manager default moved from v1.19.2 to the current v1.21.2; `kubernetes_manifest` looks for the CRD at plan time, so the issuers moved into a Helm chart.

## [x] Phase 7 - Helm and GitOps
- [x] helm/collector, helm/api, helm/frontend: probes wired to the services' health groups, resource limits, secret reference (generated DB password), TLS Ingress (frontend only), HPA on the api
- [x] helm/data: one PostgreSQL and one Redis, a database per environment (`kesinti_int`, `kesinti_prod`) and a separate Redis logical DB
- [x] Argo CD (via Terraform), 7 Applications plus the root Application under `gitops/apps`, values in `gitops/int` and `gitops/prod`
- [x] `promote-int` (service tag -> INT values -> Argo CD) and `promote-prod` (manual, promotes via PR)
- [x] Addresses: https://int.kesinti.localhost and https://kesinti.localhost, Argo CD https://argocd.localhost
- [x] 1.0.1 was cut: the GHCR 1.0.0 images predated the hardening pass

Status: done (2026-10-02). Notes: [07-helm-and-gitops.md](07-helm-and-gitops.md). `helm lint` is clean on all five charts, `kubectl --dry-run=server` passed on four, `terraform validate` is clean. On the first install Argo CD had all 8 Applications Synced/Healthy within 3 minutes. `make cluster-check` verifies it with 25 checks: both environments' home page and `/env.json` with the right label, map summary and source status at 200, the boundary file at 475 KB, SSE connecting through the Ingress (`:bagli`, `retry:3000`, a heartbeat every 15 s), three certificates from our own CA, scanning off in INT and on in PROD, the HPA at 1-3. The PROD database holds 18,799 rows from live scans; INT is empty (no scanning there). Cluster plus compose use 4.0 GB of RAM. Where I got stuck: `/api` returned 502 through the Ingress (nginx's resolver cannot resolve the short service name, it needed the fully qualified one); the cluster was running older code than compose (hence 1.0.1); there is no `applicationSet.enabled` key (replicas 0 instead); the first version of `promote-prod.yml` was invalid YAML (unindented lines inside a block scalar), so a step validating the workflow files was added.

Done when (met, with the local wording): `helm lint` and `helm template` are clean; int and prod are Synced/Healthy in Argo CD; the app is live on `https://kesinti.localhost` instead of a public domain.

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
