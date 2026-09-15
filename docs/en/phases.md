# Phases

Türkçe (the original): [../tr/fazlar.md](../tr/fazlar.md)

This file is the full definition of the project's 9 phases. The Turkish file holds the original text word for word; this is a translation of it. Later decisions don't change the original definition, they live in the "Changes by decision" section at the bottom. Progress: [PROGRESS.md](PROGRESS.md).

## Working rules (original, translated)

Follow the architecture, data model and repo layout in the plan file; if you need to deviate from the plan, say why and get approval.

First create docs/PROGRESS.md: write the phases below as a checklist and add a "done when" condition under each phase. Then start Phase 1. At the end of every phase:
- tests and build must be green
- a commit must be made
- the TR and EN phase documents must be written
- PROGRESS.md must be updated
- give me a short summary and STOP, don't move on to the next phase on your own

## Original definition (translated)

### Phase 1 - Discovery and skeleton
- Examine the outage pages of BEDAŞ, AYEDAŞ, İSKİ and İGDAŞ (with curl, a few requests per source). For each: URL, data format (HTML table, JSON endpoint, behind a form), fields, planned/unplanned split, difficulties. Write the result to docs. If a source is very hard, say so and propose dropping it from v1.
- Save sample responses from each source as fixtures.
- Monorepo skeleton: services/collector, services/api, frontend, helm, gitops, infra, loadtest, docs, .github/workflows.
- docker-compose.yml: postgres, redis, the two services, frontend. Everything must come up with `docker compose up` (the services may return an empty health endpoint for now).
- README (TR/EN): what the project is, how to run it.

### Phase 2 - Collector
- Common Outage model and SourceCollector interface.
- BEDAŞ, AYEDAŞ, İSKİ collectors; each with fixture-based tests.
- Normalize date/time and province/district/neighbourhood names (Turkish characters, upper/lower case, suffixes like "MAH.").
- Two separate schedules per source: fault/current outage page every 5 minutes, planned outage page every 15 minutes. Intervals configurable per source. If an institution gives both on one page, that page is scanned every 5 minutes.
- Add a random delay of a few seconds (jitter) to requests, so all sources aren't hit in the same second.
- Publish only changes: keep a hash of every record, and write records that are new, changed or gone compared to the previous scan to a Redis Stream (NEW / UPDATED / GONE). If nothing changed, nothing goes to the stream.
- Prometheus metrics: collector_last_success_timestamp{source}, collector_items_total{source}, collector_errors_total{source}.
- One source failing must not stop the others.

### Phase 3 - API
- Stream consumption (consumer group), upsert by dedup_key, Flyway migrations.
- Endpoints: GET /api/outages (type, il, ilce, active filters), GET /api/outages/{id}, GET /api/map/summary (Redis cache), GET /api/sources (last successful scan time per source), GET /api/stream (SSE).
- Publish an SSE event the moment an outage is added, changed or ended (outage.created / outage.updated / outage.ended). The summary of the changed district must be updated in the cache too.
- Since several api pods will run, SSE events must not be tied to one pod: events are distributed to all pods through Redis Pub/Sub, and each pod forwards them to its own connected clients.
- If the SSE connection drops the client must be able to reconnect (Last-Event-ID support, periodic heartbeat).
- Actuator health (separate liveness/readiness), /actuator/prometheus.
- Integration tests with Testcontainers.

### Phase 4 - Frontend
- React + Vite + Leaflet. Find an openly licensed GeoJSON for Turkey's province/district borders, write its license to docs, add it to the repo.
- Colour districts by active outage count and type; type filter (electricity/water/gas); clicking a district shows the outage list (neighbourhoods, times, source link).
- Updates over SSE without reloading; a newly arrived outage stands out on the map with a short highlight animation.
- Data freshness indicator: "Last update: 2 min ago" and last scan time per source (/api/sources). If a source is delayed the user must see it.
- Live connection status: connected / reconnecting icon.
- Version and environment label in the corner (from a build arg/env), "What's new" dialog (from CHANGELOG).
- Must look right on mobile.

### Phase 5 - Containers and CI
- Multi-stage Dockerfile for each service, non-root user, small base image. nginx for the frontend.
- GitHub Actions: one workflow per service, paths filter; build + test + Maven/npm cache; JaCoCo coverage threshold; SonarQube Cloud analysis and quality gate; Trivy image scan (fails on CRITICAL); on tags (collector-vX.Y.Z, api-vX.Y.Z, frontend-vX.Y.Z) push to GHCR and create a GitHub Release.
- CHANGELOG.md.
- In the YAPMAN GEREKEN (your part) section: SonarQube Cloud setup and the SONAR_TOKEN secret, making the GHCR packages public.
- At the end of the phase give me the commands to push the v1.0.0 tags.

### Phase 6 - Infrastructure
- infra/terraform: Hetzner server (CX23), firewall (22 only from my IP, 80/443 open), SSH key, k3s installed with cloud-init. Variables in tfvars.example.
- Steps to get the kubeconfig locally.
- cert-manager + Let's Encrypt ClusterIssuer (staging first, then prod).
- YAPMAN GEREKEN: Hetzner account and API token, domain name and DNS A record. Give me the commands to run Terraform myself; don't apply it yourself.

### Phase 7 - Helm and GitOps
- helm/collector, helm/api, helm/frontend: probes, resource limits, ConfigMap, secret reference, Ingress (TLS), HPA for api.
- Lightweight Helm charts for PostgreSQL and Redis; one Postgres, a separate database per environment.
- Argo CD installation; int and prod Applications under gitops/apps; values files under gitops/int and gitops/prod.
- Flow: when a new image is released, CI updates the tag in the INT values (work out and explain how this won't clash with the repo's branch rules); promotion to PROD through a PR that moves the INT tag to PROD.
- Addresses: int.<domain> and <domain>.
- At the end of the phase v1.0 must be live.

### Phase 8 - Observability
- kube-prometheus-stack with values trimmed to fit into 4 GB (turn off unneeded components, short retention).
- ServiceMonitors.
- Grafana dashboards (JSON in the repo, loaded by provisioning): source health, application (requests, latency, errors, cache hit), cluster (pods, CPU/memory, HPA).
- Alert: send a Telegram notification if there is no successful scan for 30 minutes from fault pages, or 3 hours from planned outage pages.
- The dashboard should also show the number of connected SSE clients and the time it takes for an event to get from the database to the browser. YAPMAN GEREKEN: Telegram bot token and chat id.
- A way to break a source temporarily to test the alert.

### Phase 9 - v1.1 and resilience
- İGDAŞ collector (natural gas); gas filter and colour in the frontend; CHANGELOG; take it through the whole pipeline: automatic to INT, PR to PROD.
- loadtest/: k6 spike scenario (normal load, then a 10x jump). Measure HPA scaling and the effect of the cache, write the results to docs.
- Rollback exercise: take PROD back to the previous version, then bring it forward again.
- Demo runbook (TR/EN): write the demo scenario from the plan command by command.

## Changes by decision

The original definition above stays unchanged. This section shows the decisions that touch the phase definition. Details are in the phase notes.

| Date | Phase | Change | Status | Where |
|---|---|---|---|---|
| 2026-09-11 | Phase 1, 2 | AYEDAŞ dropped from v1: data is behind a form with reCAPTCHA | decided | [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md) |
| 2026-09-11 | Phase 1, 2 | The token embedded in İSKİ's site is not used. İSKİ data comes from the "Su Kesintileri" file on İBB Open Data (historical, newest 2024-02-19) | decided | [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md) |
| 2026-09-11 | Phase 2 | The İBB dataset is scanned once a day. General rule: scan frequency follows how often the source updates, at most every 5 minutes (CLAUDE.md updated) | decided | CLAUDE.md, [../plan.md](../plan.md) |
| 2026-09-11 | Phase 2 | robots.txt check before every request. Sources that block with a captcha, a WAF or robots.txt are on a blocklist | decided | [01-source-survey.md](01-source-survey.md) |
| 2026-09-11 | Phase 2 | Adding AEDAŞ, ÇEDAŞ, KCETAŞ (electricity) and İZSU (live water) to the v1 sources | decided (approved 2026-09-13) | [01-source-survey.md](01-source-survey.md) |
| 2026-09-11 | Phase 3 | Nullable `external_id`, `lat`, `lon` in the data model. Dedup: `(source, external_id)` when there is an `external_id`, the hash otherwise | decided | [data-model.md](data-model.md) |
| 2026-09-11 | Phase 8 | Alert threshold of 26 hours for open data sources scanned once a day (30 min for faults and 3 h for planned stay as they are) | proposal, written into the plan | [../plan.md](../plan.md) |
| 2026-09-11 | Phase 9 | İGDAŞ can't be crawled (robots.txt `Disallow: /`). Başkentgaz doesn't publish outages, İzmirgaz only has per-street queries. The gas item is on hold. Request drafts for İGDAŞ and Başkentgaz are ready | decided | [requests/](requests/) |
| 2026-09-11 | Phase 9 | ASKİ (Ankara live water faults) instead of gas in v1.1, backups BUSKİ and MESKİ | decided (approved 2026-09-13) | [01-source-survey.md](01-source-survey.md) |
| 2026-09-11 | v2.1 | İSKİ historical data will be used in the neighbourhood report card | decided | [../plan.md](../plan.md) |
| 2026-09-13 | Phase 2 | The İBB water outage file (`iski/ibb-su-kesintileri-2023-2024.xlsx`) stays in the repo as a fixture | decided | [01-source-survey.md](01-source-survey.md) |
| 2026-09-13 | Phase 2, 8 | A `feed` label on the collector metrics next to `source` (to separate the 30 min fault / 3 h planned alerts) | decided (approved 2026-09-13) | [02-collector.md](02-collector.md) |
| 2026-09-13 | Phase 3 | The API gets a browser UI: Swagger UI (REST) and a small SSE page showing live events. The map UI stays in Phase 4 | decided (user request) | [03-api.md](03-api.md) |
| 2026-09-14 | Phase 4 | District boundaries from OCHA HDX COD-AB (HGK data, CC BY-IGO), as simplified TopoJSON instead of GeoJSON. Turkish spelling of province and district names from Wikidata (CC0) | decided | [04-frontend.md](04-frontend.md) |
| 2026-09-14 | Phase 4, 5 | CHANGELOG.md (and CHANGELOG.en.md) started in Phase 4 instead of Phase 5, for the "What's new" window. The build context of the frontend image is now the repo root | decided | [04-frontend.md](04-frontend.md) |
| 2026-09-15 | Phase 5 | Trivy only fails on CRITICAL vulnerabilities that have a published fix (`ignore-unfixed`) | decided | [05-container-and-ci.md](05-container-and-ci.md) |
| 2026-09-15 | Phase 5, 7 | The environment label (LOCAL/INT/PROD) is not a build argument but `APP_ENV` at runtime (nginx `/env.json`), so the same image can move from INT to PROD | decided | [05-container-and-ci.md](05-container-and-ci.md) |
| 2026-09-15 | Phase 5 | The image is built and scanned on every run but only pushed to GHCR on a tag (`X.Y.Z` and `latest`). Without `SONAR_TOKEN` the Sonar step is skipped with a warning | decided | [05-container-and-ci.md](05-container-and-ci.md) |
| 2026-09-15 | Phase 5 | A fourth workflow next to the three per-service ones: `compose-smoke` builds the whole stack with compose and tries it end to end through nginx | decided (user approved) | [05-container-and-ci.md](05-container-and-ci.md) |

When a pending approval is settled, a new row is added to this table. The original definition is never changed.
