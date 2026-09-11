# Phase 1 - Discovery and skeleton

Date: 2026-09-11

Two jobs in this phase: find out how the four sources (BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ) serve their data, and set up an empty monorepo that actually starts.

## Source discovery

All requests were made with curl and the User-Agent `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`. I checked robots.txt first, then for each source the home page, the outage page and the JS files the page uses. Recorded samples live under `services/collector/src/test/resources/fixtures/<source>/`.

Short version: if we stick to our own rules (respect robots.txt, don't get around captchas or access control), BEDAŞ is the only source that can be read cleanly in v1. Each of the other three has a blocker; details and my recommendation are below.

| Source | Type | robots.txt | How data is served | Status |
|---|---|---|---|---|
| BEDAŞ | Electricity (European side) | none (404) | Open JSON endpoints | fine for v1 |
| AYEDAŞ | Electricity (Asian side) | allows everything | Address form + reCAPTCHA | drop from v1 |
| İSKİ | Water | none (404) | JSON API that needs an embedded Bearer token; WAF on the map site | drop from v1 (or your call) |
| İGDAŞ | Natural gas | `Disallow: /` | Not examined | cannot be crawled because of robots.txt |

### BEDAŞ

Two separate feeds, both JSON, no authentication.

**Planned outages: `GET https://www.bedas.com.tr/GetItemsData`**

The page `www.bedas.com.tr/elektrik-kesintisi-sorgulama` asks for province/district and queries per district with `POST /elektrik-getir`. But on load it calls `GET /GetItemsData`, which returns the list for all districts in one go. So one request is enough instead of 25 per-district requests.

- Size: about 170 KB, 218 records across 25 districts when I tried.
- Date range: from yesterday to 3 days ahead (10.09 - 14.09).
- Record shape:

```json
{
  "version": 1,
  "insertDateTime": "2026-09-11T00:02:04.25+03:00",
  "updateDateTime": "2026-09-11T11:52:06.93+03:00",
  "id": "36876717",
  "plannedOutage": {
    "reason": "Yatırım / Ekonomik Ömür Sebebi ile Yenileme",
    "city": "İSTANBUL", "city2": "", "city3": "",
    "county": "ARNAVUTKÖY", "county2": "", "county3": "",
    "startDateTime": "2026-09-10 09:00:00",
    "endDateTime": "2026-09-10 17:00:00",
    "message": "İSTANBUL ARNAVUTKÖY ilce MERKEZ-BOLLUCA mah SÜMELA sk  bölgelerinde ...",
    "lat": " 41.212111",
    "lon": " 28.772530"
  }
}
```

- There is no separate neighbourhood field; the neighbourhoods are inside the `message` text: `<PROVINCE> <DISTRICT> ilce <NEIGHBOURHOOD> mah <STREET>, <STREET> sk / <NEIGHBOURHOOD2> mah ... sk  bölgelerinde`. We will have to parse them out of that text.
- Times come without a time zone (`2026-09-10 09:00:00`); we treat them as Europe/Istanbul.
- `lat`/`lon` are strings with a leading space.
- One of the 218 records has `city2`/`county2` filled (an outage spanning two districts). Rare, but the parser has to handle it.
- `id` and `updateDateTime` exist, useful for change tracking.
- Fixtures: `bedas/planned-getitemsdata.json`, and a per-district example `bedas/planned-elektrik-getir-arnavutkoy.json`.

**Current outages: `GET https://kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages`**

`kesinti.bedas.com.tr` is a Vite SPA. Its bundle contains the `kesintiapi.ckenerji.com.tr/BEDAS/...` endpoints.

- 73 rows and 19 distinct `OUTAGE_NO` when I tried. Each row is one transformer, so the same outage repeats over several rows.
- Fields: `OUTAGE_NO`, `BILDIRIM_TURU` (`Bildirimli` / `Bildirimsiz`), `RPTD_DATE` (ISO, +03:00), `EST_REPAIR_TIME`, `SURE`, `SCADA_INITIATED`, `XFMR_ID`, `CBS_TM_NO`, `MESSAGE`.
- `Bildirimsiz` means a fault (7 rows, message "Şebeke arızası, ekip çalışıyor..."). `Bildirimli` is a planned outage that is happening right now.
- The biggest problem: rows have no district/neighbourhood names, only a transformer number (`CBS_TM_NO`). Location comes from `GET /BEDAS/GetLocation?tmno=<no>`, which returns `{"results":[{"ilce":"GAZİOSMANPAŞA","mahalle":"BARBAROS HAYRETTİN PAŞA"}]}`. BEDAŞ's own site calls this for every transformer every 5 minutes, in batches of 30. We won't: transformers don't move, so we'll cache `transformer -> district/neighbourhood` in Redis for a long time and only ask for transformers we haven't seen before. I expect a few dozen requests on the first scan and a handful per scan after that, sent one by one with a delay.
- `RetrieveOutageTransformersList` gives transformer polygons. v1 has no neighbourhood polygons, so we don't use it.
- Fixtures: `bedas/unplanned-retrieve-outages.json`, `bedas/getlocation-28175.json`.

**Planned vs unplanned, and overlap:** a planned outage that is in progress shows up both in `GetItemsData` and in `RetrieveOutages` (`Bildirimli`), but the ids differ (for example `36878730` vs `4851785`) and don't match up easily. My proposal for Phase 2: take planned outages only from `GetItemsData` and faults only from the `Bildirimsiz` rows of `RetrieveOutages`. That way the same outage isn't counted twice.

**Schedule:** `RetrieveOutages` every 5 minutes, `GetItemsData` every 15 minutes.

### AYEDAŞ

- robots.txt (`www` and `online` subdomains): everything allowed.
- The only place with outage information is `https://online.ayedas.com.tr/elektrik-kesintisi-sorgulama`. There is no listing page, and the site map didn't show any other outage page.
- The page is an address form: province, district, sub-district, town, neighbourhood, street. Submitting it calls `POST /elektrik-kesintisi-sorgulama`. The response has `planlananKesintiListe` and `mevcutKesintiListe`, with `ilAdi`, `ilceAdi`, `mahalleAdi`, `sokakAdi`, `kesintiTipi`, `polygon` fields. The structure is actually very good.
- Blocker: the form has Google reCAPTCHA (`CaptchaValueCheck` in `FormValidation`, the server returns `state: 3` on captcha failure) plus a `__RequestVerificationToken`. Even a district-level query needs a solved captcha.
- A captcha is the site saying "no automated queries". Trying to get around it (captcha solving services and so on) is not something this project does.
- **Recommendation: drop AYEDAŞ from v1.** What's left is asking AYEDAŞ/Enerjisa for data access, or adding it if they publish open data.
- Fixtures: `ayedas/elektrik-kesintisi-sorgulama.html` (a record of the form and its JS; no parser can be written against it), `ayedas/robots-www.ayedas.com.tr.txt`.

### İSKİ

- robots.txt: none for `iski.istanbul` and `iskiapi.iski.istanbul` (404).
- Page: `https://iski.istanbul/abone-hizmetleri/ariza-kesinti`. A Nuxt SPA, no data in the HTML.
- The page's JS fetches data from `https://iskiapi.iski.istanbul/api/iski/bolgeselAriza/listesi` and `.../bolgeselAriza/arizaDetayiFiltreli?ilceKodu=&mahalleKodu=`. Fields used in the template: `ilceKodu`, `mahalleAdi`, `arizaNeviAciklamasi`, `baslamaTarihi`, `tahminiBitisTarihi`.
- Blocker 1: without an `Authorization` header the API returns `403 Forbidden`. A fixed Bearer token is embedded in the site's JS and an axios interceptor adds it to every request.
- Blocker 2: on `harita.iski.gov.tr`, which the fault records link to, my requests for JS files were rejected by a WAF with `Request Rejected`. That shows non-browser clients are blocked on purpose. I stopped sending requests to İSKİ after that.
- Planned vs unplanned: I only saw a fault list, no separate planned outage page.
- Technically we could take the token from the bundle and use it. But that means using a credential that wasn't given to us. It can change at any time, and the WAF shows how they feel about bots. Putting the token in the repo is against our rules anyway.
- As an alternative I looked at the İBB Open Data Portal. The "İSKİ Duyuruları" (İSKİ announcements) dataset is a set of yearly XLSX files, last updated in 2024-03. The "Su Kesintileri" (water outages) and fault count datasets are historical statistics too. They're no use for a live map, but they could be a history source for the neighbourhood report card planned for v2.1.
- I made a mistake here: `data.ibb.gov.tr/robots.txt` says `Disallow: /api/` and `Crawl-Delay: 10`. I fetched robots.txt in the same script before the CKAN API calls but didn't stop on its result, so 2 requests went to `/api/3/action/...`. I sent nothing more there. In the collector the robots.txt check will be done in code (Phase 2), so a disallowed request never leaves. In the discovery scripts I now check robots.txt first and only then continue.
- **Recommendation: drop İSKİ from v1** and write to İSKİ to ask for access to live fault data. If you decide to go ahead with the token, it lives in a Kubernetes Secret, never in the repo, and we accept that the collector breaks whenever the token changes. That's your call.
- Fixtures: `iski/bolgeselariza-listesi-403.json` (response without a token), `iski/ariza-kesinti-page-shell.html`, `iski/harita-waf-rejected.html`.

### İGDAŞ

- `https://www.igdas.istanbul/robots.txt` and `https://www.igdas.com.tr/robots.txt`: both say `User-agent: *` / `Disallow: /`.
- Our rules include a robots.txt check, so I didn't send a single request to the outage page. A web search shows İGDAŞ has an address-based query screen, but crawling it would go against robots.txt.
- The İBB Open Data Portal has İGDAŞ datasets for consumption and subscriber counts, but no outage dataset.
- **Recommendation: İGDAŞ cannot be crawled.** The "İGDAŞ collector" item in Phase 9 can't be done as written. Options: ask İGDAŞ for permission or access, or start gas with another distributor whose robots.txt allows it (the v1.2 list in the plan has Başkentgaz and İzmirgaz).
- Fixtures: `igdas/robots-www.igdas.istanbul.txt`, `igdas/robots-www.igdas.com.tr.txt`.

### How many requests I sent

Per source: BEDAŞ 9 (including robots and the APIs), AYEDAŞ 5, İSKİ about 10 (with JS files; I stopped at the WAF), İGDAŞ 2 (robots.txt only), İBB Open Data 3 (robots.txt and the 2 API calls mentioned above).

### A note on the data model

The `outage` table in the plan is enough for BEDAŞ. I suggest two additions and will add them in Phase 3 if you approve:
- `external_id`: the source's own id (`plannedOutage.id`, `OUTAGE_NO`). When the source provides an id, it's a more solid dedup key than a hash.
- `lat`, `lon`: BEDAŞ gives coordinates for planned outages; until neighbourhood polygons arrive we could show them as points.

## Skeleton

### What I set up

- `services/collector` and `services/api`: Spring Boot 4.1.1 (the latest stable release right now), Java 21, Maven. For now only `spring-boot-starter-webmvc`, actuator and the Prometheus registry. Liveness/readiness probes are on (`/actuator/health/liveness`, `/actuator/health/readiness`) and `/actuator/prometheus` is exposed. Each service has a test that checks these three endpoints over real HTTP.
- `frontend`: React 19 + Vite 8. For now just a title and a version/environment label in the corner (`VITE_APP_VERSION`, `VITE_APP_ENV` come from build args). nginx proxies `/api/` to the api service. Buffering is off for `/api/stream`; SSE will need that in Phase 3.
- `docker-compose.yml`: postgres 18, redis 8, collector, api, frontend. Each has a healthcheck and a `mem_limit`. Services don't start until their dependencies are healthy.
- Empty folders (`helm/*`, `gitops/*`, `infra/*`, `loadtest`, `.github/workflows`) are kept with `.gitkeep`.
- `.gitignore` excludes `.env`, `*.tfvars`, `*.tfstate`, `kubeconfig*`, `*secret*.yaml` from the start. Only `*.example` files get in.

### Why like this

- The Dockerfiles are simple two-stage builds for now (build with Maven, run on JRE alpine, non-root user). The real hardening is in Phase 5. Compose needed them already for `--build`.
- The JVMs get `-XX:MaxRAMPercentage=75`, so heap follows the container limit. The server will have 4 GB, so I wanted this to be a habit from day one.
- The frontend image is `nginx-unprivileged` and listens on 8080 without root. It's mapped to 3000 on the host.
- The Postgres 18 image moved its data directory under `/var/lib/postgresql`, so the volume is mounted there (the old `/var/lib/postgresql/data` path gives a warning on 18).

### Result

- `mvn verify`: collector 3/3, api 3/3 tests green.
- `npm run build` (in a node:24-alpine container): green.

- `docker compose up --build`: all five containers healthy. collector and api return `{"status":"UP"}` on `/actuator/health`, the frontend serves the page on 3000, and a request to `localhost:3000/api/...` goes through nginx to the api (404 because there are no endpoints yet, which is expected).
- Idle memory: collector 129 MiB / 384, api 131 MiB / 512, postgres 41 MiB, redis 7 MiB, frontend 13 MiB. About 320 MiB in total, a comfortable start for a 4 GB server.

### Where I got stuck

- The frontend container came up `unhealthy` the first time, even though `/healthz` answered from the host. The health log said `wget: can't connect to remote host: Connection refused`. On Alpine `localhost` resolves to `::1` first. The nginx-unprivileged image normally adds an IPv6 listen to `default.conf`, but I had replaced that file with my own config, so nginx only listened on IPv4. Switching the healthcheck to `127.0.0.1` fixed it.

- Running commands from Windows with `wsl.exe -- bash -c '...'` expanded `$VARIABLES` to empty strings, because wsl.exe passes the command line through another shell. The first robots.txt attempt went to `https://robots.txt/`. Fix: write the commands to a script file and run `wsl.exe -- bash script.sh`.
- Node isn't installed in WSL. I generated the frontend's `package-lock.json` and ran its build in a `node:24-alpine` container, so there's no need to install Node on the machine.
- The İSKİ server is slow; a 100 KB JS file took a few minutes.

## Open decisions (yours)

1. If AYEDAŞ, İSKİ and İGDAŞ are dropped, v1 is BEDAŞ only (electricity, European side). Should Phase 2 go ahead with BEDAŞ only, or should we look for another source for v1?
2. Do you want to go ahead with İSKİ's embedded token? My recommendation is no.
3. What replaces the İGDAŞ item in Phase 9?
4. Should `external_id` and `lat`/`lon` be added to the data model?
