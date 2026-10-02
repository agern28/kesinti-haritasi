# Phase 9 - v1.1 and resilience

This phase has two jobs in the plan: a new source for v1.1 (natural gas first, ASKİ if that fails) and the resilience work (a k6 spike scenario, measuring the HPA, a rollback drill, a demo runbook).

The source side closed again; below is every door I knocked on and why each one was ruled out. All the resilience work is done and measured.

## A new source: checked again, still none

Phase 1 found no natural gas source. At the start of this phase I re-checked all of them (2026-10-02).

| Candidate | State | Why it was ruled out |
|---|---|---|
| İGDAŞ (gas, Istanbul) | `robots.txt`: `User-agent: *` / `Disallow: /` | The site does not allow crawling. Same as in Phase 1 |
| ASKİ (water, Ankara) | The page exists: `Kesinti.aspx`, titled "Ankara Su Arızaları" | The list is not in the GET response; it is an ASP.NET WebForms form that needs province/district/neighbourhood selected and posted with a VIEWSTATE. That is dozens of POSTs per scan, the same reason AYEDAŞ and İzmirgaz were ruled out |
| Başkent EDAŞ (electricity, Ankara) | The outage page is behind reCAPTCHA | Captcha. And it is a CK Enerji company, so our parser was ready: `GetItemsData` 301s to a 404, and `kesintiapi.ckenerji.com.tr/<code>/RetrieveOutages` returned 404 for every code I tried |
| UEDAŞ (electricity, Bursa) | `robots.txt` allows, and the table headers are exactly what we want (date, time, reason, province, district, neighbourhood, street) | The rows are not in the HTML: they come from a POST to `/planli-kesintiler/sec.asp` with a province/district/neighbourhood selection, answered in XML. It is also planned outages only, 48 hours ahead |
| KCETAŞ faults | The site has "fault reporting" | A reporting form, not a list. We already collect its planned outages |
| Ankara metropolitan open data | `data.ankara.bel.tr`, `acikveri.ankara.bel.tr` | The hostnames do not resolve; checked over DoH, there is no A record (not our DNS problem) |
| BUSKİ (water, Bursa) | The name resolves | The connection fails (TLS/timeout), the page could not be fetched |
| ADM, MEDAŞ | The hostnames I tried do not exist | No A record |

So the map stays at six sources (BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ, İZSU, and İSKİ through İBB Open Data). None of the reasons are about parsing difficulty; they all land in the same place: either the site refuses crawling (robots, captcha) or the data only comes out of a query form, and the second means dozens of requests per scan, which the politeness rule in CLAUDE.md rules out.

So v1.1 ships resilience and operations work rather than a new source.

## Spike scenario (k6)

`loadtest/spike.js`: normal traffic, a tenfold spike, then a drop. Two endpoints are measured separately because one is cached and the other is not:

- `/api/map/summary`: what the map asks for on load, cached in Redis for 10 minutes.
- `/api/outages?il=...&ilce=...`: imitates clicking a district, goes to the database.
- `/api/sources`: the source status box, less often.

`scripts/loadtest.sh` (that is, `make loadtest`) runs k6 while sampling the HPA every 10 seconds and printing where the replica count changed.

Measured (2026-10-02, PROD, local cluster, two nodes):

| What | Value |
|---|---|
| Duration | 5 minutes (1 min normal, 30 s ramp, 2 min at the peak, 30 s down, 1 min normal) |
| Virtual users | 10 → 100 → 10 |
| Requests | 16,661 (55.3 req/s), 10,816 iterations |
| Errors | 0 (`http_req_failed` 0.00%) |
| Summary endpoint | avg 4.26 ms, p95 6.73 ms, worst 109 ms |
| District list endpoint | avg 4.48 ms, p95 7.15 ms, worst 57 ms |
| All requests | p95 6.75 ms |
| Received | 128 MB (424 kB/s) |

What the HPA did (sampled every 10 seconds):

```
07:47:04  replicas=1 desired=1 cpu=3%     <- normal traffic
07:48:37  replicas=1 desired=3 cpu=157%   <- spike, the HPA decided
07:48:58  replicas=3 desired=3 cpu=196%   <- three replicas 21 seconds later
```

Peak CPU was 196% of the request (150m), average 72%. After the test CPU fell to 12-16% and the HPA went back to one replica once the 300 second `stabilizationWindowSeconds` passed. All three pods became ready and k6 saw no errors.

Takeaways:

- **The cache works.** The summary endpoint the map asks for on load answers with a p95 of 6.73 ms under 100 virtual users; the 10 minute Redis cache keeps that load off the database.
- **The indexes work.** The district list, which does go to the database, has a p95 of 7.15 ms. Without the partial indexes added after Phase 5 this would be a table scan over 19,000 rows.
- **The limit is CPU, not the application.** p95 stayed at 7 ms while CPU reached 196%, so the constraint is the CPU share requested. Three replicas fit on a 2 vCPU server, and the HPA's ceiling of 3 is reasonable here; more would need a bigger node.
- **Scaling is quick.** The decision came 90 seconds after the spike and three replicas 21 seconds after the decision. There were no errors in between, so a single replica carried the load by queueing.

## Rollback drill

A rollback is just another commit: the image tag in `gitops/prod/api.yaml` changes, Argo CD sees it and applies it. The cluster is never touched by hand and what happened when stays in git.

There is a difference between 1.0.0 and 1.0.1 you can see: a deep-paging request (`page=2000&size=100`) returns 400 on 1.0.1 and 200 on 1.0.0 (the code from before the hardening pass).

Measured (2026-10-02):

| Step | Time | Behaviour check |
|---|---|---|
| 1.0.1 → 1.0.0 (rollback) | 30 seconds after the push all pods were on 1.0.0 and the rollout was complete | deep paging 200 (as expected) |
| 1.0.0 → 1.0.1 (roll forward) | 34 seconds | deep paging 400 (as expected) |

My first attempt measured the wrong thing: I checked "target version ready" with `readyReplicas >= 1`, which is also true in the middle of a rollout, and the old pods were still answering, so the behaviour looked like the new version even on the old one. The right check is that every pod of the deployment runs the target image and `rollout status` has completed.

## Demo runbook

[demo-runbook.md](demo-runbook.md): a 15-20 minute walkthrough, command by command. Preparation checklist, showing the app, the two environments, GitOps (including a hand-made cluster change being reverted), the release path, the Grafana dashboards, the alert drill, the load test, the rollback and a closing checklist. The FAQ covers the certificate warning, the `*.localhost` addresses and why INT is empty.

## Where I got stuck

- **`yq` is not on this machine.** The promotion workflows use it because the GitHub runner image ships it; locally it is not installed. In the rollback drill the tag was edited with Python instead.
- **`git pull --rebase` refuses a dirty tree.** Documents were being written during the drill; `git -c rebase.autoStash=true pull --rebase` solved it.
- **The first rollback measurement was wrong** (above): mid-rollout the old pods still answer.
- **Dead hostnames.** Several addresses in the source hunt refused to connect. To tell that apart from our own DNS breaking, I checked over DoH (`https://1.1.1.1/dns-query`): `data.ankara.bel.tr` and the ADM/MEDAŞ names I tried have no A record, so the addresses really are wrong. Without that check I could have misdiagnosed it as "DNS broke again".
- **Recovery after the alert drill is not instant.** The NetworkPolicy came off at 07:58:42 and the first successful scans landed at 08:01:50: that gap is the scan interval (5 minutes), not the robots cache. Thanks to the Phase 5 fix ("reuse a valid cached robots copy when the fetch fails") robots was not the problem.
- **Installing k6.** There is no package in the repositories, so the binary came from the GitHub release and was verified against the official checksum list (`~/.local/bin/k6`, no sudo needed).
