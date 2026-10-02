# Kesinti Haritası (Outage Map)

[![collector](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml)
[![api](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml)
[![frontend](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml)
[![compose-smoke](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml)
[![infra](https://github.com/agern28/kesinti-haritasi/actions/workflows/infra.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/infra.yml)

An app that collects electricity, water and natural gas outages in Turkey (starting with Istanbul) from official sources and shows them on a single map. The map is live: when a new outage lands in the database it shows up without a page reload.

This is also a DevOps internship project. How the app is run matters as much as the app itself: CI, containers, Terraform, k3s (locally for now, via k3d), Helm, GitOps with Argo CD, monitoring with Prometheus/Grafana.

Türkçe: [README.md](README.md)

## Architecture

```
source sites (BEDAŞ, AYEDAŞ, İSKİ, ...)
        |
   collector  (Spring Boot, scheduled scans)
        |
   Redis Streams  (outage-events)
        |
   api  (Spring Boot, REST + SSE)  ---  PostgreSQL, Redis cache
        |
   frontend  (React + Leaflet, nginx)
```

Full plan (Turkish): [docs/plan.md](docs/plan.md). Progress: [docs/en/PROGRESS.md](docs/en/PROGRESS.md).

## Repository layout

| Folder | Contents |
|---|---|
| `services/collector` | Service that collects data from the sources |
| `services/api` | REST + SSE API |
| `frontend` | React + Vite + Leaflet UI |
| `helm` | Helm charts for the services plus `cluster-issuers` (cert-manager issuers) |
| `gitops` | Argo CD Applications and per-environment values |
| `infra` | `k3d/` local k3s cluster definition, `terraform/local` for what runs on it |
| `loadtest` | k6 scenarios |
| `docs` | Phase notes (`tr/`, `en/`) |

## Running locally

You need Docker and Docker Compose. Java and Node are only needed to work on the services outside containers (Java 21, Maven 3.9, Node 22.12+).

```bash
make up      # creates .env with a random password if missing, builds images, waits for health
make check   # end-to-end check against the running stack
```

The same thing without make:

```bash
cp .env.example .env   # change the password, .env never goes into the repo
docker compose up --build
```

What comes up:

| Service | Address |
|---|---|
| frontend | http://localhost:3000 |
| api | http://localhost:8080/actuator/health |
| collector | http://localhost:8081/actuator/health |
| postgres | localhost:5432 (user name and password from `.env`) |
| redis | localhost:6379 |

Both services expose metrics at `/actuator/prometheus`.

### Day to day

| Command | What it does |
|---|---|
| `make ps` | Service status and ports |
| `make check` | 15 checks: nginx, api routes, SSE, database, source health |
| `make logs-collector` | Which source was scanned when, how many records came back |
| `make stop` / `make up` | Stop / bring back up |
| `make reset` | Wipes everything including the database and Redis, rebuilds |
| `make smoke` | Runs the CI end-to-end test locally (local stack must be down) |
| `make test` | Service and frontend tests (outside containers) |

Full list: `make help`.

Containers run with `restart: unless-stopped`, so the stack comes back on its own after a machine or Docker restart; anything stopped with `make stop` stays down. On Docker Desktop this needs its own autostart setting (Settings > General > Start Docker Desktop when you sign in).

Data lives in the `pgdata` and `redisdata` Docker volumes and `make down` leaves them alone. Outage history keeps accumulating; use `make reset` to start clean.

Large NEW/GONE counts on the first scans after downtime are normal: the source lists moved on while the stack was off.

### Local Kubernetes cluster

Next to compose there is a k3s cluster on the same machine: k3d runs it inside Docker, and Terraform sets up everything on top of it (namespaces, cert-manager, certificate issuers). The only extra tool needed is k3d, plus `kubectl`, `helm` and `terraform`.

```bash
make cluster-up      # creates the cluster, then terraform apply for what runs on it
make cluster-status  # nodes, pods, issuers
make cluster-down    # deletes the cluster completely
```

The app is installed on the cluster by Argo CD: the Applications under `gitops/apps` bring up both environments.

| What | Address |
|---|---|
| PROD | https://kesinti.localhost |
| INT | https://int.kesinti.localhost |
| Argo CD | https://argocd.localhost (`admin`, password: `make argocd-password`) |
| Grafana | https://grafana.localhost (`admin`, password: `make grafana-password`) |

Certificates come from our own in-cluster CA, so the browser warns; continue anyway.

```bash
make cluster-check     # end-to-end check of the cluster install
make argocd-apps       # sync and health of the Applications
make argocd-refresh    # make Argo CD check the repo right away
make alerts            # state of the alert rules
make prometheus        # Prometheus UI (localhost:9090)
```

Observability: Prometheus, Alertmanager and Grafana run on the cluster, and three dashboards live in the repo as JSON (source health, application, cluster). Alerts fire when scanning stops; to get Telegram notifications, put a bot token and chat id into `terraform.tfvars`. Details: [docs/en/08-observability.md](docs/en/08-observability.md).

What differs between the environments lives in the values files under `gitops/int` and `gitops/prod`: database, Redis logical DB, HPA, environment label, and the source scanning that is off in INT. Details: [docs/en/07-helm-and-gitops.md](docs/en/07-helm-and-gitops.md). The cluster itself, and what moving to the cloud (Hetzner) would take: [docs/en/06-infrastructure.md](docs/en/06-infrastructure.md).

Note: a source site is only ever hit from one place at a time. While the cluster is up, the collector that scans is the PROD one; stop the compose collector (`docker compose stop collector`).

### Working outside containers

```bash
# Service tests
cd services/collector && mvn verify
cd services/api && mvn verify

# Frontend (expects the api on localhost:8080)
cd frontend && npm install && npm run dev
cd frontend && npm test

# Regenerate the district boundaries (needs python3 and npx, writes public/geo/ilceler.topo.json)
cd frontend && python3 scripts/ilceler.py
```

District boundaries come from OCHA HDX COD-AB (data from the General Command of Mapping, CC BY-IGO). Details: [docs/en/04-frontend.md](docs/en/04-frontend.md).

## Data sources

How each source is read and why some are left out for now: [docs/en/01-discovery-and-skeleton.md](docs/en/01-discovery-and-skeleton.md).

We try to be gentle with the sources: unplanned outage pages are scanned every 5 minutes, planned outage announcements every 15 minutes, requests get random jitter, the User-Agent carries the project name and repo link, and robots.txt is respected.
