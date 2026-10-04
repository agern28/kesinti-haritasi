# Phase 8 - Observability

After Phase 7 the app ran on the cluster, but we only noticed breakage by looking. This phase installed Prometheus, Alertmanager and Grafana, wrote dashboards for the source scans, the application and the cluster, and defined alerts that speak up when scanning stops.

| What | Address |
|---|---|
| Grafana | https://grafana.localhost (`admin`, password: `make grafana-password`) |
| Prometheus | `make prometheus` -> http://localhost:9090 |
| Alertmanager | `make alertmanager` -> http://localhost:9093 |
| Alert state | `make alerts` |

## Why the install is split in two

**Terraform** installs kube-prometheus-stack; **Argo CD** installs our own objects (ServiceMonitors, alert rules, dashboards).

The reason: Alertmanager's Telegram receiver needs a bot token and a chat id. Neither may enter the repo (the rule in CLAUDE.md), so both live in `terraform.tfvars`, which is in `.gitignore`. That puts Alertmanager's routing on the Terraform side too.

I first tried the `AlertmanagerConfig` CRD: it can read the token from a secret, but its `chatID` field is a number and cannot come from a secret, so the chat id would have to be committed. Instead Alertmanager's own `config` block is used: the token is mounted as a file (`bot_token_file`) and the chat id comes from tfvars.

Everything that can live in git does: the `helm/monitoring` chart and `gitops/apps/monitoring.yaml`.

## Fitting into 4 GB

Monitoring of components k3s does not expose is off: etcd, kube-controller-manager, kube-scheduler, kube-proxy. Left on, they would show as permanently down and raise their own alerts.

Prometheus keeps 2 days of data with a 3 GiB cap and scrapes every 30 seconds. Every component has a memory limit: Prometheus 1 GiB, Grafana 384 MiB, Alertmanager 128 MiB, kube-state-metrics 128 MiB, node-exporter 64 MiB, the operator 192 MiB.

Measured: with the monitoring stack up, WSL reports 5.0 GB in use in total (cluster + applications + monitoring). The compose stack was stopped at this point; with both running there is no room on a 4 GB machine.

## What gets scraped

`helm/monitoring` installs two ServiceMonitors. Both live in the `monitoring` namespace and reach into both application namespaces with `namespaceSelector`, so there is no need to install the chart per environment.

Prometheus targets (`make prometheus` -> Targets): four, all `up`.

```
kesinti-prod  collector   up
kesinti-prod  api         up
kesinti-int   collector   up
kesinti-int   api         up
```

One detail: the `job` label does not come from the ServiceMonitor's name but from the **Service's** name. So rules say `job="api"`, not `job="kesinti-api"`. My first rules matched nothing because of this.

No metrics had to be added to the services: what Phase 3 instrumented already covers everything this phase wants.

| Metric | What it is for |
|---|---|
| `collector_last_success_timestamp{source,feed}` | Every staleness alert is built on this |
| `collector_items_last_scan`, `collector_items_total` | How many records a source returns |
| `collector_errors_total`, `collector_scan_duration_seconds` | Errors and duration |
| `collector_events_total{event}` | NEW / UPDATED / GONE |
| `api_sse_clients` | Browsers holding the map open |
| `api_sse_delivery_seconds` | How long an event takes from the database to a browser (histogram) |
| `api_summary_cache_total{result}` | Summary cache hit ratio |
| `api_stream_events_total{event,result}` | Events consumed from the stream |

## Alerts

| Alert | Threshold | Why |
|---|---|---|
| `KesintiArizaTaramasiDurdu` | 30 minutes | Fault pages are scanned every 5 minutes |
| `KesintiPlanliTaramasiDurdu` | 3 hours | Planned announcements every 15 minutes |
| `KesintiAcikVeriTaramasiDurdu` | 26 hours | The İBB dataset is scanned daily (2026-09-11 decision) |
| `KesintiTaramaHatasiArtiyor` | 3 errors in 15 minutes | To see trouble before the threshold hits |
| `KesintiServisAyaktaDegil` | 5 minutes | Prometheus cannot scrape the service |
| `KesintiSseGecikmesiYuksek` | p95 > 5 s | The live map is lagging |
| `KesintiApiHataOrani` | 5% 5xx | The api is failing |

**The alerts only look at PROD.** Scanning is off in INT, so `collector_last_success_timestamp` stays at 0 there and `time() - 0` evaluates to something like 29 million minutes. Without the `namespace="kesinti-prod"` filter, INT alone would keep every alert firing.

Something else surfaced while building this: the "oldest scan" stat initially covered every feed and showed 9 hours because of `ISKI/daily`. That source is scanned once a day, so that is normal. The stat was split in two: minutes for the fault and planned feeds, and a separate stat in hours for the daily open-data source (threshold 26 hours).

## Dashboards

Three dashboards live in the repo as JSON (`helm/monitoring/dashboards/`) and are installed as ConfigMaps; Grafana's sidecar picks up ConfigMaps labelled `grafana_dashboard`. Nothing has to be added through the Grafana UI and the dashboards are in version control.

- **Source health**: oldest scan (fault/planned), last scan of the daily source, errors in the last hour, total records in the latest scans, time since last success per feed (every scan resets the line; a line going straight up means scanning stopped), records per feed, NEW/UPDATED/GONE events, average scan duration.
- **Application**: SSE client count, event delivery time to the browser (p50/p95), summary cache hit ratio, request rate, requests by status, average response time by route, events consumed from the stream.
- **Cluster**: running pods, restarts in the last hour, node memory use, the HPA's current replica count, CPU and memory per pod, how close pods are to their memory limit, HPA current vs desired replicas.

Edits made in the UI do not stick: the sidecar reloads the file and they are gone. Changes belong in the JSON in the repo.

Request latency shows an average, not p95: Spring Boot does not publish histogram buckets for `http_server_requests`. SSE delivery does have buckets, so that panel computes a real p95.

## Testing the alerts

The cleanest way to break a source on purpose is to stop the collector:

```bash
make alarm-testi        # stops the PROD collector
make alerts             # watch the state
make alarm-testi-bitir  # bring it back
```

`make alarm-testi` does two things: scales the deployment to zero and turns Argo CD's `selfHeal` off. The second is essential, otherwise Argo CD brings the pod back within seconds and the test never starts.

Expected flow: 30 minutes after scanning stops the rule's threshold is crossed, and because of `for: 5m` it goes `pending` first and then `firing`. With Telegram configured a message arrives. After `make alarm-testi-bitir` the first successful scan clears the alert on its own and a "resolved" message follows.

I ran it for real; the timeline (2026-10-02, UTC):

| Time | What happened |
|---|---|
| 07:24:57 | The NetworkPolicy went on, cutting the collector's egress |
| 07:27:37 | The first failed scans showed up in the metrics (`java.net.ConnectException`) |
| 07:42:14 | `KesintiTaramaHatasiArtiyor` became active |
| 07:47:39 | The same alert went `firing`, active in Alertmanager for four feeds |
| 07:51:44 | Staleness crossed 30 minutes, `KesintiArizaTaramasiDurdu` became active |
| 07:57:40 | That alert went `firing` too |
| 07:58:42 | The NetworkPolicy was deleted (`make alarm-testi-bitir`) |
| 08:05:23 | The last feed to recover: the AEDAŞ fault scan finished successfully |
| 08:05:48 | `KesintiArizaTaramasiDurdu` went `inactive` on its own |

Recovery took 7 minutes because of the scan interval plus one detail: the AEDAŞ fault scan takes 208 seconds. CK Enerji fault records only carry a transformer number, so locations are fetched with separate requests (up to 40 per scan) and we wait at least 2 seconds per host. That makes this feed's own duration 3.5 minutes, which leaves little room inside its 5 minute interval. Worth watching: if the source gets slower, the scan will not keep up with its own schedule.

`KesintiTaramaHatasiArtiyor` stays up for a while after the fix because it still counts errors inside its 30 minute window. That is expected.

Note what does *not* fire: `KesintiServisAyaktaDegil` compares `up == 0`, and scaling a deployment to zero removes the target from service discovery entirely, so there is no `up` series to be zero. That alert covers a pod that is up but unscrapable, not a pod that is gone. A deployment scaled to zero is caught by the staleness alerts.

## Telegram

The token and chat id go into `terraform.tfvars` and never into the repo:

```hcl
telegram_bot_token = "123456:ABC..."
telegram_chat_id   = "-1001234567890"
```

Then `make bootstrap` (or `terraform apply`).

Decided (2026-10-04): Telegram was left unconnected for this local project. Having the alerts visible in the Alertmanager UI and in `make alerts` was judged enough; the receiver and the tfvars fields are in place and the notification works as soon as a token is added.

While both are empty, Alertmanager routes alerts to the "bos" (empty) receiver: they show up in the Alertmanager UI and in `make alerts`, and go nowhere else. Monitoring works without Telegram; only the notification is missing.

## Where I got stuck

- **Grafana would not start.** With a 256 MiB memory limit and the chart's default liveness probe (60 seconds) Grafana 12 was killed before it finished booting; the pod stayed at 2/3 and the Ingress returned 503. The limit went to 384 MiB and the liveness initial delay to 120 seconds. Grafana needs about 90 seconds to come up on this machine.
- **A Terraform state lock.** While Helm was waiting for Grafana to become healthy (`wait = true`), WSL stopped responding for a while and the command was cut off; the `terraform apply` kept running in the background, so the next apply said "Error acquiring the state lock". The fix is not to force-unlock: check for a running process (`pgrep -a terraform`) and wait if there is one. Here, patching Grafana with `kubectl` made the waiting apply finish on its own.
- **The Prometheus container has no `wget` or `curl`.** Reading targets and rules from the API needs `kubectl port-forward`, which is what `scripts/alerts.sh` does.
- **A heredoc cannot feed a Python program that also reads stdin.** `curl ... | python3 - <<'PY'` makes Python read the heredoc as its program and leaves the piped data unread (it exits with SIGPIPE). The Python moved into `scripts/alerts.py`.
- **The `job` label comes from the Service name** (above).
- **`AlertmanagerConfig`'s `chatID` cannot come from a secret** (above).
