# Phase 5 - Container and CI

Date: 2026-09-15

Türkçe: [../tr/05-container-ve-ci.md](../tr/05-container-ve-ci.md)

The images of the three services were tightened up, and each service got a GitHub Actions workflow: tests with a coverage floor, SonarQube Cloud, image build and Trivy scan, and on a tag, a push to GHCR and a GitHub Release. Everything was tried locally. To run on GitHub they need a push, and Sonar needs to be set up (see "TODO FOR YOU" below).

## Images

| Image | Base | User | Size |
|---|---|---|---|
| collector | `eclipse-temurin:21-jre-alpine` | 10001 | 360 MB |
| api | `eclipse-temurin:21-jre-alpine` | 10001 | 379 MB |
| frontend | `nginxinc/nginx-unprivileged:1.29-alpine` | 101 | 83 MB |

- **Multi-stage**: Java is built in the Maven image, the runtime image only has the JRE. The frontend is built in the Node image, nginx only gets `dist/`.
- **Layered jar**: Spring Boot's `jarmode=tools extract --layers` splits the jar into four layers: dependencies, loader, snapshot dependencies and application code. Dependencies sit in the lower layer, so when the code changes only the top layer of a few hundred KB is pulled again. The app starts through `JarLauncher` (the api in 4.3 s).
- **Build cache**: the Maven repository and the npm cache live in a BuildKit cache mount. As long as the pom or the lock file doesn't change, dependencies aren't downloaded again.
- **Non-root, numeric UID**: 10001 in the Java images. Kubernetes' `runAsNonRoot` check can't work with a user name, it needs a number (Phase 7). nginx-unprivileged already runs as 101.
- **OCI labels**: `org.opencontainers.image.source` links the GHCR package to the repo; the version is in the `org.opencontainers.image.version` label.

### The environment label at runtime

In Phase 4 the environment label (LOCAL/INT/PROD) was a build argument. But in the Phase 7 flow the same image goes to INT first and then to PROD; a label baked in at build time would show "INT" on PROD. I moved the label to runtime: the nginx config is a template in the image (`/etc/nginx/templates/default.conf.template`), nginx fills it in with the container's `APP_ENV` on startup, and `/env.json` returns it. The frontend reads `/env.json` on load; if it doesn't come (`npm run dev`), the default is LOCAL. The version number is part of the image and stays a build argument. Compose sets `APP_ENV: LOCAL`; on INT and PROD it will come from the Helm values.

## Workflows

`.github/workflows/collector.yml`, `api.yml`, `frontend.yml`. All three have the same structure:

- **Triggers**: push and PR on `main`, only when the service's directory (plus `CHANGELOG.md` for the frontend) or the workflow file changes (`paths`). The `collector-v*`, `api-v*`, `frontend-v*` tags. Manual runs (`workflow_dispatch`).
- **test job**:
  - Java: `mvn verify`. Tests (the api uses Testcontainers with PostgreSQL and Redis; GitHub's runner has Docker) and JaCoCo's line coverage floor: 85%. On 2026-09-15 collector was at 91.0%, api at 91.8%.
  - Frontend: `npm run test:coverage` (Vitest, line floor 50%; measured 53.5%) and `npm run build`.
  - The coverage report is uploaded as an artifact.
  - SonarQube Cloud: the Maven plugin for Java, `sonarqube-scan-action` for the frontend. Both with `sonar.qualitygate.wait=true`, so a red quality gate fails the job.
- **image job** (if the tests pass):
  - Build with Buildx and the GitHub Actions cache (`type=gha`).
  - Trivy: `CRITICAL`, only vulnerabilities with a published fix (`ignore-unfixed`); if it finds one, the job fails.
  - SBOM: CycloneDX from Trivy, as an artifact.
  - On a tag: push to GHCR as `ghcr.io/agern28/kesinti-haritasi/<service>:X.Y.Z` and `:latest`, then a GitHub Release. The release notes are the `## [X.Y.Z]` section of `CHANGELOG.md`, with the SBOM attached.

### compose-smoke: the services together

The service workflows test each service on its own; none of them tries the connections in between (nginx to the api, the stream to the browser). The nginx 502 bug below was also caught by a check I ran by hand. So there is a fourth workflow: `compose-smoke.yml`, with the logic in `.github/scripts/compose-smoke.sh`. The whole stack is built from scratch with compose and tried through nginx:
- The user of the three containers (10001, 10001, 101).
- `/`, `/geo/ilceler.topo.json`, `/env.json`, `/api/outages`, `/api/map/summary`, `/api/sources` through nginx.
- The data path: a fake event is written to the Redis Stream; does `outage.created` arrive over SSE, and is the record in the api and in the map summary. The collector's scanning is off (`COLLECTOR_SCHEDULING_ENABLED=false`), CI doesn't go to the live sources.
- The api is recreated and forced onto a new IP (a temporary container takes the IP it freed); does nginx still reach it without the frontend restarting.

The script also runs locally: as a separate compose project (`kesinti-smoke`) with its own volumes, it doesn't touch local data and deletes everything at the end. The ports are the same, so it doesn't run while the local stack is up.

### Decisions

- **Actions pinned by SHA**: like `actions/checkout@3d3c42e...  # v7.0.1`. Tags can be moved; if an action's tag is hijacked, a pinned SHA isn't affected. Updating is manual, together with the version in the comment.
- **Least privilege**: `contents: read` at the workflow level. Write permissions (`packages`, `contents`) only in the image job. On PRs from forks the token is read-only anyway, and the GHCR and Release steps only run on a tag.
- **Trivy `ignore-unfixed`**: nothing can be done about a vulnerability without a fix; failing on it would lock the pipeline for no reason. It does fail on vulnerabilities with a fix, and it did on the first scan (below).
- **GHCR only on a tag**: on every push to `main` the image is built and scanned but not pushed. This may change in Phase 7 for the automatic rollout to INT (CI will update the tag in the INT values).
- **Sonar is skipped without a token**: if `SONAR_TOKEN` isn't set, a warning is printed instead of the Sonar step and the job stays green. That way the workflows aren't red until the token is added, and they still work on fork PRs. Once the token is there, the quality gate is mandatory.

## Trivy failed on the first scan

When I ran the new images through Trivy locally, collector and api had three CRITICAL vulnerabilities in Tomcat 11.0.24 (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525), fixed in 11.0.25. Spring Boot 4.1.1 is the latest release and ships 11.0.24. `tomcat.version` is pinned to 11.0.25 in both poms; the line will be removed with Spring Boot's next patch (there's a note in the pom). After that all three images were clean.

## Sonar's first findings

The quality gate was green (its conditions look at new code), but the first analysis showed 7 findings in the existing code. I went through all of them before v1.0.0:

| Where | Finding | What I did |
|---|---|---|
| api `OutageRepository.search` (2 vulnerabilities) | Dynamically built SQL | A false alarm: only fixed fragments from the code were added to the query, every value from the user was a parameter. Still, the search was turned into a single fixed query: a filter that isn't given has a null parameter, the condition is `(:x is null or column = :x)`. The filter tests passed unchanged. |
| api `OutageRepository.map` (bug) | Possible NullPointerException | The `starts_at` column is NOT NULL, but the code assumed it; if it's ever empty, the outage isn't counted as active. |
| collector `HttpResult` (bug) | `byte[]` in a record | The record's own `equals`/`hashCode` compare the array by reference; they now compare the body's content, and `toString` prints the body's size instead of the body. A test was added. The first version failed the gate on new code coverage (76.5% against 80%): four branches of `equals` (another type, a different URI, a different content type, a null body) weren't tested; tests were added. |
| api `SseStreamTest` (bug) | Description after the assertion | A real test bug: `.as(...)` came after the assertion, so the description was never shown. The order was fixed. |
| frontend `WhatsNew` (2 minor bugs) | Clickable backdrop without keyboard support | The backdrop now only closes when it is clicked itself and listens for Esc; when the dialog opens, focus moves to the "Close" button. Two tests were added. |

## What I tried locally

- `mvn verify` green in both services, JaCoCo floor met (collector 82 tests, api 24 tests).
- Frontend: 37 tests, coverage floor and build green.
- All three images built (169 s the first time), five containers healthy in compose. `id` in the containers: `uid=10001(app)`. `/env.json` returned `{"environment":"LOCAL"}`.
- Trivy (same settings as CI): no CRITICAL vulnerability with a fix in any of the three images.
- `actionlint` is clean on all three workflows.
- I compared every input used in the workflows with the action's `action.yml` at the pinned SHA; nothing missing.

On GitHub (2026-09-15, first push to `main`): the collector, api and frontend workflows were green on their first run. The test jobs took 20 s to 2 min, the image jobs (build, Trivy, SBOM) 1-2.5 min. `compose-smoke` failed on its first run (below); after the fix all 14 checks passed. The Sonar step was skipped with a warning since there is no `SONAR_TOKEN`. After the push I cloned the repo into an empty directory and set it up from scratch following the README: the tests of all three services and the smoke test passed there too. GHCR and the Release wait for the first tag, Sonar for its setup.

## Where I got stuck

- **The Tomcat vulnerabilities Trivy found** (above). I couldn't wait for Spring Boot's next patch; bumping the version on its own is the known way.
- **Wrong actionlint flag**: there's no `-color=never`, it's `-no-color`. The error output also hid the result of the input comparison.
- **A false alarm from my input comparison script**: it said `actions/checkout` has no `fetch-depth`; looking at `action.yml` by hand, it does. The parsing in the script trips over some description lines. The workflow is correct.
- **The frontend returned 502 after the api was recreated**: after rebuilding the images and recreating the api container, the nginx in the frontend started returning 502 on `/api` and the map stayed on "Yeniden bağlanıyor" (reconnecting). nginx resolves the name in `proxy_pass http://api:8080` once at startup; when the new container got a different IP, it kept going to the old one. The Phase 4 "stop the api, start it again" check didn't catch this, because there the container and its IP stay the same. Now the address is a variable (`API_UPSTREAM`) and nginx re-resolves the name through `resolver` every 10 seconds. On Kubernetes this wouldn't have been a problem since a Service IP is stable, but the resolver address is different there (kube-dns); both are environment variables and will come from the Helm values in Phase 7.
- **compose-smoke failed on its first run on GitHub**: the smoke test that passed locally and in a fresh clone failed on GitHub while forcing the api onto a new IP. I gave the IP the api freed to the temporary container with `--ip`; the Docker on the GitHub runner only accepts that on networks with a user-configured subnet, and compose's default network isn't one. Now the temporary container joins the network without asking for an IP and gets the next free one; the check that the api's new IP really differs is still there. The other 13 checks had passed on the first run too.
- **Sonar's first analysis came out red**: once `SONAR_TOKEN` was added, the Sonar step failed in all three workflows with "QUALITY GATE STATUS: FAILED"; the tests and the analysis itself had succeeded. The reason: every condition of the "Sonar way" gate looks at new code, and on the first analysis there is no period to compare with, so the gate wasn't computed (status `NONE` on SonarCloud, empty condition list) and `sonar.qualitygate.wait=true` counts that as a failure. On the second analysis the new code period started from the first one and the gate was `OK` in all three projects. It only happens once, at setup; if a new Sonar project is created, expect its first run to be red.
- **The first v1.0.0 tags failed at Sonar**: tagging `944eb64`, which had passed the gate on `main`, made all three tag runs fail at the Sonar step, so the image and Release steps never ran. SonarCloud treats a tag as a separate, short-lived branch (like `refs/tags/collector-v1.0.0`); on that branch's first analysis there was no period to compare with, so the gate wasn't computed. Since the tagged commit has already been analyzed and passed the gate on `main`, the Sonar step no longer runs on tag runs. GitHub reads the workflow from the tag's commit on a tag run, so after the fix the tags were moved to the new commit; the first tags had produced neither an image nor a Release. The rule stays the same: a tag goes on a commit that is green on `main`.
- **The environment label**: if I'd left it as a build argument, the "same image from INT to PROD" flow of Phase 7 would have broken. I noticed while designing the images, so it didn't wait until Phase 7.

## Found later: keeping dependencies current (2026-09-17)

Two things were pinned by hand in this phase: the actions by commit SHA and Tomcat to 11.0.25 in the poms. Both go stale on their own, and nobody gets told when a security patch lands. `.github/dependabot.yml` was added: GitHub Actions, the Maven dependencies of both services, the frontend's npm dependencies and the base images of the three Dockerfiles are checked weekly. The PRs it opens go through the service workflows and compose-smoke, so an update can't merge without passing the tests.

## Known limitations

- Until the Sonar token is added, the Sonar step is skipped with only a warning.
- Images are amd64 only. Hetzner CX23 is x86, enough for now.
- The browser check of the UI (Phase 4, Playwright) isn't in CI.
- There is a `latest` tag, but the GitOps side (Phase 7) will always use explicit version tags.
- The Java images are 360-380 MB. Most of that is the JRE itself; shrinking it with jlink isn't needed for now.

## TODO FOR YOU

1. **SonarQube Cloud**
   1. Sign in at https://sonarcloud.io with your GitHub account.
   2. Use "Import an organization" to add the `agern28` account as an organization (free plan, public repo).
   3. Use "Analyze new project" to pick the `kesinti-haritasi` repo. With the monorepo option, create three projects with exactly these keys: `agern28_kesinti-haritasi_collector`, `agern28_kesinti-haritasi_api`, `agern28_kesinti-haritasi_frontend`.
   4. In each project, turn off "Automatic Analysis" under Administration > Analysis Method (analysis comes from CI, the two don't work together).
   5. Create a token under My Account > Security.
2. **SONAR_TOKEN**: on GitHub, repo > Settings > Secrets and variables > Actions > New repository secret. Name `SONAR_TOKEN`, value the token from the previous step.
3. **Make the GHCR packages public**: after the first tags, `kesinti-haritasi/collector`, `api` and `frontend` will show up under Packages on your GitHub profile. In each, Package settings > Change visibility > Public.

## v1.0.0 tags

Once all three workflows are green on `main` and Sonar is set up:

```bash
cd ~/projects/kesinti-haritasi
git checkout main && git pull
git tag -a collector-v1.0.0 -m "collector 1.0.0"
git tag -a api-v1.0.0 -m "api 1.0.0"
git tag -a frontend-v1.0.0 -m "frontend 1.0.0"
git push origin collector-v1.0.0 api-v1.0.0 frontend-v1.0.0
```

Each tag starts its own workflow: tests, Trivy, push to GHCR, Release. The release notes come from the `[1.0.0]` section of `CHANGELOG.md`.

Result (2026-09-15): the first tags failed at the Sonar step (above, "Where I got stuck"). After the fix the tags were moved to `11f3377`; all three tag runs are green, the images are on GHCR as `ghcr.io/agern28/kesinti-haritasi/{collector,api,frontend}:1.0.0` and `:latest`, the three GitHub Releases have their notes from the CHANGELOG and an SBOM each. Since the repo is public, the packages came out public; they can be pulled without logging in, no manual visibility change was needed.
