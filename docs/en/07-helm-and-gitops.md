# Phase 7 - Helm and GitOps

Phase 6 left an empty k3s cluster. This phase put the app on it: four Helm charts, Argo CD, two environments (`kesinti-int` and `kesinti-prod`) and the CI path that moves a new version into INT and promotes it to PROD. All of it local; what the cloud route would need is at the end of [06-infrastructure.md](06-infrastructure.md).

Addresses:

| What | Address |
|---|---|
| PROD | https://kesinti.localhost |
| INT | https://int.kesinti.localhost |
| Argo CD | https://argocd.localhost (user `admin`, password: `make argocd-password`) |

Certificates come from our own CA from Phase 6, so browsers warn; the chain is real and visible with `openssl`.

## The charts

`helm/collector`, `helm/api`, `helm/frontend` and `helm/data`. The same pattern in all of them:

- Probes point at the health groups the services already expose: readiness includes the database and Redis, liveness does not. So when Redis goes away a pod stops taking traffic but is not restarted. That split was made in `application.yml` back in Phase 3; the chart only calls the right path.
- Resource requests and limits on every container, sized so the total fits a 2 vCPU / 4 GB server (the rule in CLAUDE.md).
- Non-root user: 10001 in the Java images, 101 in nginx-unprivileged. `readOnlyRootFilesystem` is on, with `emptyDir` for the JVM's `/tmp` and for nginx's `conf.d` and cache directories.
- `JAVA_TOOL_OPTIONS` matches the image default (`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`) but can be overridden from values.

Per-service parts:

- **Only the frontend has an Ingress.** nginx proxies `/api` in the same origin (exactly as under compose), so SSE needs no second entry point and no CORS setup. TLS terminates at the Ingress; inside the cluster it is http.
- **The HPA is on the api**: CPU 70%, 1-3 replicas. `scaleDown.stabilizationWindowSeconds: 300`, because SSE connections are long-lived and killing a pod immediately would force browsers to reconnect. `terminationGracePeriodSeconds: 45` for the same reason.
- **One collector replica.** A second replica would hit the same source twice; the service that needs to scale is the api.

## The data layer

`helm/data`: one PostgreSQL and one Redis for the cluster, in the `kesinti-data` namespace. The environments are kept apart like this:

- A separate database in PostgreSQL: `kesinti_int` and `kesinti_prod`, created by a script dropped into `docker-entrypoint-initdb.d` that leaves existing databases alone.
- A separate Redis logical DB: 0 for int, 1 for prod. Stream and cache keys have the same names but live in different databases.

Both are StatefulSets with PVCs from k3s's `local-path` provisioner. No operator, no replication, no backups: on a local cluster with a 4 GB target those cost more than they are worth. This is the part that changes in the cloud (a managed PostgreSQL or an operator).

The PostgreSQL password is not in the repo: Terraform generates it with `random_password` and writes it into the `kesinti-db` secret in all three namespaces, and the charts read it with `secretKeyRef`. The password lives in the Terraform state, which is in `.gitignore`. In the cloud this would need an external secret store.

## Argo CD and app-of-apps

Terraform installs Argo CD (`infra/terraform/local/argocd.tf`): dex and notifications off, ApplicationSet at zero replicas, memory limits on what remains. `server.insecure=true`, because Traefik terminates TLS.

Terraform does not install the applications. It installs only the root Application (`helm/argocd-root`), which watches the `gitops/apps` directory in this repo. Every file there is an Argo CD Application:

```
gitops/apps/data.yaml            -> kesinti-data
gitops/apps/int-collector.yaml   -> kesinti-int
gitops/apps/int-api.yaml
gitops/apps/int-frontend.yaml
gitops/apps/prod-collector.yaml  -> kesinti-prod
gitops/apps/prod-api.yaml
gitops/apps/prod-frontend.yaml
```

Adding a service or an environment means adding a file, not applying anything to the cluster by hand.

Each Application has two sources: one is the chart path (`helm/api`), the other points at the same repo as `ref: values` and provides the values file (`$values/gitops/int/api.yaml`). The chart stays in one place and the differences live under `gitops/int` and `gitops/prod`:

| Value | INT | PROD |
|---|---|---|
| database | `kesinti_int` | `kesinti_prod` |
| Redis logical DB | 0 | 1 |
| collector scanning | off | on |
| HPA | off, one pod | on, 1-3 |
| `APP_ENV` | INT | PROD |
| address | int.kesinti.localhost | kesinti.localhost |

Both sync automatically (`prune` and `selfHeal` on). Argo CD does not create namespaces (`CreateNamespace=false`); those belong to Terraform.

## INT does not touch the source sites

The politeness rule in CLAUDE.md: one place at a time hits a source. If both environments scanned, the source sites would see twice the requests. So `gitops/int/collector.yaml` sets `scheduling.enabled: false`: in INT the service is up and its health and metrics are there to look at, only the scheduled scans are off. The one collector that scans is PROD's.

For the same reason the compose collector was stopped (`docker compose stop collector`). `make cluster-check` checks this too: if the compose collector is running, the check fails.

## The promotion path

Two workflows were added.

**promote-int.yml**: when a service tag is pushed (`api-v1.0.1`) it updates `image.tag` in `gitops/int/api.yaml` and commits to `main`. Argo CD sees that commit and deploys it to INT. It can also be run by hand (`workflow_dispatch`, choosing service and version).

On conflicts with branch rules: this workflow pushes to `main` directly with `GITHUB_TOKEN`. If `main` becomes protected (required reviews or status checks), a direct push is rejected; the step then has to become a pull request, the same way `promote-prod.yml` does it. There is no risk of a loop: no workflow watches `gitops/**`, so this commit starts no new run.

**promote-prod.yml**: triggered by hand. It opens a PR that writes the version running in INT into `gitops/prod/<service>.yaml`. Going to PROD is a decision, so it is not automatic; when the PR merges, Argo CD moves PROD to that version.

## 1.0.1: actually running the path

When the install was done, `make cluster-check` caught something: a deep-paging request returned 400 under compose but 200 in the cluster. The reason is that the cluster runs the GHCR images tagged `1.0.0`, which were built at the v1.0.0 tag, before the hardening pass. The cluster was running older code than compose.

The right fix was to cut a new release, which also meant exercising the whole promotion path: a 1.0.1 section in both CHANGELOGs, a `-v1.0.1` tag for each of the three services, CI building the images, pushing them to GHCR and opening Releases, `promote-int` updating the INT values, Argo CD deploying them to INT, then `promote-prod` promoting to PROD.

## Verification

`make cluster-check` runs 25 checks: all 8 Argo CD Applications Synced/Healthy, pods Running in all three namespaces, three certificates ready and signed by our CA, both environments' home page and `/env.json` with the right label, map summary and source status at 200, the boundary file at full size, SSE connecting through the Ingress, both environment databases present, data accumulating in PROD, scanning off in INT and on in PROD, the compose collector stopped, the HPA at 1-3.

Measured:

- On the first install Argo CD had all 8 Applications Synced/Healthy within 3 minutes.
- On its first round the PROD collector wrote 18,379 new records from the İSKİ file; the PROD database holds 18,799 rows.
- The INT api answers the summary query in 8 ms with an empty database.
- SSE through the Ingress behaves exactly as under compose: `:bagli`, `retry:3000`, a heartbeat every 15 seconds.
- The cluster plus what is left of compose uses 4.0 GB of RAM (out of the 7.6 GB WSL gets).

## Where I got stuck

- **502 on `/api` through the Ingress.** Static files were fine, api requests were not. nginx's `resolver` directive does not use the search list from `/etc/resolv.conf`: it asks CoreDNS for the short name in `http://api:8080` exactly as written and gets NXDOMAIN. Under compose Docker's DNS resolves the short name, so it never showed up. The chart now derives `API_UPSTREAM` as `http://api.<namespace>.svc.cluster.local:8080` when the value is left empty.
- **The cluster was running older code** (the 1.0.1 section above). The lesson: an image tag points at a tag, not at a commit, and "the latest code is deployed" was simply an assumption.
- **There is no `applicationSet.enabled`.** Argo CD chart 10.x has no key to disable ApplicationSet; `applicationSet.replicas=0` does it. Helm does not complain about unknown values, so the first attempt silently installed it.
- **Argo CD polls the repo every 3 minutes by default.** After pushing the fix it did not notice immediately; `make argocd-refresh` (the hard refresh annotation) triggers it without waiting.
- **The first request got a 504.** Right after a new pod came up the first `/api/map/summary` timed out; later requests took 8 ms. nginx resolving the upstream for the first time plus a cold JVM. Expected, since traffic arrives the moment the probes pass; under real load a warm-up request would help.
- **`psql -U kesinti` does not work.** There is no database named after the user, so `-d postgres` is needed. That was a bug in my own check script, not in the cluster.
