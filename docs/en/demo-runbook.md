# Demo runbook

A command-by-command walkthrough that shows the whole project in 15-20 minutes: what to run, what appears, what to say. Environment: Docker inside WSL and the local k3s cluster (Phases 6-8).

Turkish: [../tr/demo-runbook.md](../tr/demo-runbook.md).

## 0. Before the demo (10 minutes)

```bash
cd ~/projects/kesinti-haritasi
make cluster-up      # if the cluster is down: k3d + Terraform (cert-manager, Argo CD, monitoring)
make cluster-check   # is everything green
```

Checklist:
- `make cluster-check` prints "ok" everywhere.
- Four browser tabs ready: https://kesinti.localhost, https://int.kesinti.localhost, https://argocd.localhost, https://grafana.localhost
- Passwords at hand: `make argocd-password`, `make grafana-password`
- Click through the certificate warning beforehand (our own CA) so you don't lose time during the demo.
- There is data in the PROD database: `curl -sk https://kesinti.localhost/api/map/summary | head -c 200`

Keep the compose stack down (`make stop`): only one collector should hit the source sites at a time, and memory is tight.

## 1. The app (3 minutes)

Open https://kesinti.localhost.

- The map loads and districts are coloured by their number of active outages.
- Click a district: active and upcoming outages there, with neighbourhoods, times, source and a link to the announcement.
- Top right, data freshness: when each source was last scanned.
- Bottom left, connection state "Canlı" (live) and the version badge `v1.0.1 · PROD`.

What to say: the data comes from official sources, and the moment an outage lands in the database it reaches the browser over SSE with no page reload.

To show a live update (optional): open the same page in a second tab; when the collector finds a new outage the district flashes briefly. Scans run every 5 minutes so it may not happen during the demo; `make logs-collector` showing recent scans is the safer option.

## 2. Two environments (2 minutes)

```bash
curl -sk https://kesinti.localhost/env.json      # {"environment":"PROD"}
curl -sk https://int.kesinti.localhost/env.json  # {"environment":"INT"}
```

The INT map is empty: source scanning is off there, because a source is only ever hit from one place at a time.

Show where the difference comes from:

```bash
cat gitops/prod/collector.yaml   # scheduling.enabled: true
cat gitops/int/collector.yaml    # scheduling.enabled: false
```

The same image runs in both environments; even the environment label arrives at runtime (`APP_ENV`).

## 3. GitOps (3 minutes)

https://argocd.localhost → nine Applications, all Synced/Healthy. Expand `prod-api` in the tree and show the Deployment, Service and HPA.

```bash
make argocd-apps
```

What to say: nothing is `kubectl apply`-ed to the cluster by hand. The root Application watches `gitops/apps` in the repo, so adding a service or an environment means adding a file. `selfHeal` is on, so changes made directly in the cluster get reverted — you can show that live:

```bash
kubectl -n kesinti-prod scale deploy/frontend --replicas=2   # change it by hand
kubectl -n kesinti-prod get deploy frontend -w               # Argo CD pulls it back to 1 within seconds
```

## 4. The release path (3 minutes)

The flow to describe (cutting a real tag takes too long in a demo, so walk through 1.0.1's history):

1. `git tag api-v1.1.0 && git push origin api-v1.1.0`
2. CI builds the image, scans it with Trivy, pushes it to GHCR and opens a Release with notes from the CHANGELOG.
3. The `promote-int` workflow updates the tag in `gitops/int/api.yaml` and commits to `main`.
4. Argo CD picks up that commit and deploys it to INT.
5. The `promote-prod` workflow is triggered by hand and opens the PR that moves the INT version to PROD; when it merges, Argo CD moves PROD.

Where to look: the `collector/api/frontend` and `promote-int` runs in GitHub Actions, the `chore(gitops): promote ...` commits in `git log --oneline`, the image tags on GHCR.

## 5. Observability (3 minutes)

https://grafana.localhost → the "Kesinti Haritası" folder.

- **Source health**: time since the last successful scan per feed. The sawtooth is normal; a line going straight up means scanning stopped.
- **Application**: SSE client count and how long an event takes from the database to a browser (p50/p95), summary cache hit ratio.
- **Cluster**: pod CPU and memory, how close pods are to their memory limit, HPA replicas.

To prove the SSE gauge, leave the map open in a tab: "SSE bağlı istemci" goes to 1 and returns to 0 about 30 seconds after the tab closes.

## 6. Alert drill (2 minutes of narration, with waiting in the background)

```bash
make alarm-testi     # cuts the PROD collector's egress
make alerts          # after a few minutes: pending -> ALARM
make alarm-testi-bitir
```

Timing: scan errors turn into an alert in about 15-20 minutes, and the "scanning stopped" alert after 30 + 5 minutes. For a short demo, start the drill before the demo and show the alert already firing, then watch it resolve with `make alarm-testi-bitir`.

What to say: thresholds follow each source's scan frequency (faults 30 minutes, planned 3 hours, daily open data 26 hours). With a Telegram token the same alert reaches your phone.

## 7. Load test and scaling (3 minutes)

```bash
make loadtest        # about 5 minutes: normal traffic, a tenfold spike, then a drop
```

The output has the k6 summary (requests per second, p95, error rate) and then where the HPA changed its replica count. Show the cluster dashboard in Grafana at the same time for replicas and CPU.

Measured numbers: [09-v11-and-resilience.md](09-v11-and-resilience.md).

## 8. Rollback (2 minutes)

```bash
# take the PROD api back one version
SURUM=1.0.0 yq -i '.image.tag = strenv(SURUM)' gitops/prod/api.yaml
git commit -am "chore(gitops): roll PROD api back to 1.0.0" && git push
make argocd-refresh
```

A difference you can see: a deep-paging request returns 400 on 1.0.1 and 200 on 1.0.0.

```bash
curl -sk -o /dev/null -w '%{http_code}\n' 'https://kesinti.localhost/api/outages?page=2000&size=100'
```

Then go back to 1.0.1 the same way. What to say: a rollback is just another commit, the cluster is never touched by hand, and the history stays in git.

## 9. Wrapping up

```bash
make cluster-status   # final state
```

What to leave behind:
- If you ran the alert drill, did you run `make alarm-testi-bitir`?
- If you ran the rollback drill, is the version back at 1.0.1?
- Leaving the cluster up is fine; `make cluster-down` removes it (application data stays in the compose volumes).

## Questions that come up

**Why `*.localhost`?** The local cluster has no real domain and no Let's Encrypt; Traefik listens on these names and the certificates come from our own in-cluster CA. What moving to the cloud would take is at the end of [06-infrastructure.md](06-infrastructure.md).

**Why does the browser warn?** Our own CA signed the certificate and the browser does not know it. The chain is real: `openssl s_client -connect 127.0.0.1:443 -servername kesinti.localhost`.

**Why is INT empty?** To be gentle with the sources, only one collector scans at a time, and that is PROD's.

**How many sources?** Six: BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ (electricity), İZSU (water, live) and İSKİ through İBB Open Data (historical). There is still no natural gas source; why is in [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md) and [09-v11-and-resilience.md](09-v11-and-resilience.md).
