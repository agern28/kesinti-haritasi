# Kesinti Haritası (Outage Map)

[![collector](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml)
[![api](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml)
[![frontend](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml)
[![compose-smoke](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml)

An app that collects electricity, water and natural gas outages in Turkey (starting with Istanbul) from official sources and shows them on a single map. The map is live: when a new outage lands in the database it shows up without a page reload.

This is also a DevOps internship project. How the app is run matters as much as the app itself: CI, containers, Hetzner via Terraform, k3s, Helm, GitOps with Argo CD, monitoring with Prometheus/Grafana.

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
| `helm` | Helm charts for the services |
| `gitops` | Argo CD Applications and per-environment values |
| `infra` | Terraform (Hetzner) and Ansible if needed |
| `loadtest` | k6 scenarios |
| `docs` | Phase notes (`tr/`, `en/`) |

## Running locally

You need Docker and Docker Compose. Java and Node are only needed to work on the services outside containers (Java 21, Maven 3.9, Node 22.12+).

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
