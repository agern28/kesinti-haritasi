# Phase 1 - Discovery and skeleton

Date: 2026-09-11

Two jobs in this phase: find out how the four sources (BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ) serve their data, and set up an empty monorepo that actually starts. After the first discovery round some decisions were made and I did a second round for water and natural gas. Both rounds are below. In a third round I surveyed the 21 electricity distribution companies and 10 metropolitan water utilities: [01-source-survey.md](01-source-survey.md).

## Summary and decisions

| Source | Type | Decision | Why |
|---|---|---|---|
| BEDAŞ | Electricity (European side) | in v1 | Open JSON endpoints, planned and faults separate |
| İSKİ | Water | in v1, from İBB Open Data | İSKİ's own API needs an embedded token. The "Su Kesintileri" file on İBB can be downloaded under robots.txt, but it's historical data |
| AYEDAŞ | Electricity (Asian side) | not in v1 | Data only behind an address form with reCAPTCHA |
| İGDAŞ | Natural gas | no | robots.txt `Disallow: /`, no İGDAŞ outage dataset on İBB |
| Başkentgaz | Natural gas (Ankara) | no | No outage information published on the site |
| İzmirgaz | Natural gas (İzmir) | no | Only a single-street query, no list |

Decisions taken (2026-09-11):
- v1 sources: BEDAŞ and İSKİ (İBB Open Data). AYEDAŞ is not in v1.
- We don't use the token embedded in İSKİ's site.
- `external_id` and `lat`/`lon` added to the data model (all nullable). Deduplication uses `(source, external_id)` when there is an `external_id`, the hash otherwise. Details: [data-model.md](data-model.md).
- No suitable natural gas source was found; the Phase 9 item is on hold (see below).

## How I sent requests

In the first round requests were made with curl and the User-Agent `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`. I checked robots.txt first, then the home page, the outage page and the JS files the page uses.

I made a mistake in the first round: on İBB Open Data I fetched robots.txt in the same script but didn't stop on its result, so 2 requests went to a path under `Disallow: /api/`. For the second round I wrote a small helper (`polite.py`, not in the repo, only for discovery): before every request it reads the host's robots.txt, applies the longest matching rule with `*` and `$` support, doesn't send the request at all if it's disallowed, and waits `Crawl-delay` seconds between requests when there is one. Every request in the second round went through it. The robots check in the collector will be written in Java with the same logic in Phase 2.

Only the fixtures of the electricity sources proposed for v1 (BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ) and the İBB water outage file collected in Phase 2 are kept in the repo (`services/collector/src/test/resources/fixtures/`). Responses from the other sources were removed from the repo; what I saw is kept as notes in this document and in the survey report.

## BEDAŞ

Two separate feeds, both JSON, no authentication. No robots.txt (404).

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
- `lat`/`lon` are strings with a leading space. They now go into the `outage.lat`/`lon` columns.
- One of the 218 records has `city2`/`county2` filled (an outage spanning two districts). Rare, but the parser has to handle it.
- `id` -> `external_id`. `updateDateTime` is useful for change tracking.
- Fixtures: `bedas/planned-getitemsdata.json`, and a per-district example `bedas/planned-elektrik-getir-arnavutkoy.json`.

**Current outages: `GET https://kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages`**

`kesinti.bedas.com.tr` is a Vite SPA. Its bundle contains the `kesintiapi.ckenerji.com.tr/BEDAS/...` endpoints.

- 73 rows and 19 distinct `OUTAGE_NO` when I tried. Each row is one transformer, so the same outage repeats over several rows. `OUTAGE_NO` -> `external_id`, rows get grouped by it.
- Fields: `OUTAGE_NO`, `BILDIRIM_TURU` (`Bildirimli` / `Bildirimsiz`), `RPTD_DATE` (ISO, +03:00), `EST_REPAIR_TIME`, `SURE`, `SCADA_INITIATED`, `XFMR_ID`, `CBS_TM_NO`, `MESSAGE`.
- `Bildirimsiz` means a fault (7 rows, message "Şebeke arızası, ekip çalışıyor..."). `Bildirimli` is a planned outage that is happening right now.
- The biggest problem: rows have no district/neighbourhood names, only a transformer number (`CBS_TM_NO`). Location comes from `GET /BEDAS/GetLocation?tmno=<no>`, which returns `{"results":[{"ilce":"GAZİOSMANPAŞA","mahalle":"BARBAROS HAYRETTİN PAŞA"}]}`. BEDAŞ's own site calls this for every transformer every 5 minutes, in batches of 30. We won't: transformers don't move, so we'll cache `transformer -> district/neighbourhood` in Redis for a long time and only ask for transformers we haven't seen before. I expect a few dozen requests on the first scan and a handful per scan after that, sent one by one with a delay.
- `RetrieveOutageTransformersList` gives transformer polygons. v1 has no neighbourhood polygons, so we don't use it.
- Fixtures: `bedas/unplanned-retrieve-outages.json`, `bedas/getlocation-28175.json`.

**Planned vs unplanned, and overlap:** a planned outage that is in progress shows up both in `GetItemsData` and in `RetrieveOutages` (`Bildirimli`), but the ids differ (for example `36878730` vs `4851785`) and don't match up easily. In Phase 2 I'll take planned outages only from `GetItemsData` and faults only from the `Bildirimsiz` rows of `RetrieveOutages`. That way the same outage isn't counted twice.

**Schedule:** `RetrieveOutages` every 5 minutes, `GetItemsData` every 15 minutes.

## AYEDAŞ (not in v1)

- robots.txt (`www` and `online` subdomains): everything allowed.
- The only place with outage information is `https://online.ayedas.com.tr/elektrik-kesintisi-sorgulama`. There is no listing page, and the site map didn't show any other outage page.
- The page is an address form: province, district, sub-district, town, neighbourhood, street. Submitting it calls `POST /elektrik-kesintisi-sorgulama`. The response has `planlananKesintiListe` and `mevcutKesintiListe`, with `ilAdi`, `ilceAdi`, `mahalleAdi`, `sokakAdi`, `kesintiTipi`, `polygon` fields. The structure is actually very good.
- Blocker: the form has Google reCAPTCHA (`CaptchaValueCheck` in `FormValidation`, the server returns `state: 3` on captcha failure) plus a `__RequestVerificationToken`. Even a district-level query needs a solved captcha.
- robots.txt allows it, but the data sits behind the captcha. A captcha is the site saying "no automated queries". Getting around it (captcha solving services and so on) is not something this project does.
- What's left is asking AYEDAŞ/Enerjisa for data access, or adding it if they publish open data.
- Recorded responses were removed from the repo (not a v1 source). The form is described above.

## İSKİ

### İSKİ's own site (not used)

- robots.txt: none for `iski.istanbul` and `iskiapi.iski.istanbul` (404).
- Page: `https://iski.istanbul/abone-hizmetleri/ariza-kesinti`. A Nuxt SPA, no data in the HTML.
- The page's JS fetches data from `https://iskiapi.iski.istanbul/api/iski/bolgeselAriza/listesi` and `.../bolgeselAriza/arizaDetayiFiltreli?ilceKodu=&mahalleKodu=`. Fields used in the template: `ilceKodu`, `mahalleAdi`, `arizaNeviAciklamasi`, `baslamaTarihi`, `tahminiBitisTarihi`.
- Without an `Authorization` header the API returns `403 Forbidden`. A fixed Bearer token is embedded in the site's JS. That token wasn't given to us, so we don't use it (decision).
- On `harita.iski.gov.tr`, which the fault records link to, my requests for JS files were rejected by a WAF with `Request Rejected`.
- Recorded responses (the 403 without a token, the page shell, the WAF page) were removed from the repo.

### İBB Open Data (used in v1)

`data.ibb.gov.tr/robots.txt`:

```
User-agent: *
Disallow: /dataset/rate/
Disallow: /revision/
Disallow: /dataset/*/history
Disallow: /api/
Crawl-Delay: 10
```

Dataset pages (`/dataset/<name>`) and file download links (`/dataset/<uuid>/resource/<uuid>/download/<file>`) are allowed by these rules. The CKAN API (`/api/`) is not. So we read the file list from the dataset page's HTML, not from the API, and wait 10 seconds between requests.

The İSKİ organization has 18 datasets. Two looked useful:

- **İSKİ Duyuruları** (`/dataset/iski-duyurulari`): yearly XLSX files, 2019-2023. I downloaded one: columns `tarih | link | baslik`, content is press releases and event announcements. Not outage data, not used.
- **İstanbul'da Meydana Gelen Su Kesintileri** (`/dataset/istanbul-da-meydana-gelen-su-kesintileri`): two XLSX files, 2022-2023 and 2023-2024. This is the real source.

The 2023-2024 file:

- 6,410 rows, 39 districts. Date range: 2023-02-18 08:47 - 2024-02-19 11:13.
- Columns: `ILCE | KESİNTİ SEBEP | ARIZA KESİNTİ TARİHİ | ARIZA BİTİS TARİHİ | CALISMA YERİ | MAHALLE`
- Example: `ADALAR | 100 MM ÇAPLI ŞEBEKE HATTI ARIZASI | 12/02/2024 13:30:10 | 12/02/2024 20:30:00 | BURGAZADA GÖNÜLLÜ CAD.ÜZERINDE | BURGAZADA MAH`
- Date format `dd/MM/yyyy HH:mm:ss`, no time zone, treated as Europe/Istanbul. The end date is filled on every row.
- `MAHALLE` is a comma-separated list (`MADEN MAH,NİZAM MAH`) with the "MAH" suffix. Normalization will clean it up.
- No source id, so `external_id` stays null and deduplication uses the hash.
- All rows are fault outages, there is no planned/fault split. `planned = false`.

**Important limitation:** this data is not live. The newest record is from 2024-02-19 and the dataset hasn't been updated since 2024-03. It will not show any water outage that is active today. v1 will have a "water" layer, but it's only useful as past outages (how many outages a district had recently, for example). Asking İSKİ for access is still the only way to live water data.

**Schedule proposal:** files are added about once a year. Downloading them every 5 or 15 minutes makes no sense and loads İBB for nothing. My proposal: read the dataset page once a day and download a file only when a new link or a changed "Son Güncelleme" (last updated) shows up. This deviates from the 5/15 minute rule in CLAUDE.md, so I need your approval (the rule was written for outage and planned outage pages; this is a dataset).

- Fixture: `iski/ibb-su-kesintileri-2023-2024.xlsx` (kept for the parser test of the daily İBB collector in Phase 2). The announcements file was removed because it isn't outage data.

## Natural gas

### İGDAŞ

- `https://www.igdas.istanbul/robots.txt` and `https://www.igdas.com.tr/robots.txt`: both `User-agent: *` / `Disallow: /`. I didn't send a single request to the outage page.
- İGDAŞ datasets on İBB Open Data: building information, gas unit price and volume, gas consumption, monthly consumption per district, subscriber counts per district, consumption by usage class, investment type and length. None of them is outage data. The links are allowed by robots.txt, but there is no outage file to download.
- The robots.txt records were removed from the repo; their content is above.

### Başkentgaz (Ankara)

- The domain is `www.baskentdogalgaz.com.tr` (`baskentgaz.com.tr` doesn't resolve).
- robots.txt: `User-agent: *` / `Disallow:` (empty), everything allowed. The API subdomain `bskapiv1.baskentdogalgaz.com.tr` has no robots.txt (404).
- TLS: the server doesn't send the intermediate certificate (GoDaddy G2), so curl and Python fail verification. Instead of turning verification off with `-k`, I fetched the intermediate from the AIA URL in the certificate and connected with a separate CA bundle. If we used this source, the collector would need the same intermediate in its Java truststore.
- The site is a React SPA that gets its content from a CMS API under `https://bskapiv1.baskentdogalgaz.com.tr/api/`. The bundle has no outage-related endpoint. Going through the whole menu tree (`menus/ByDomainMenus/1`, 460 items), the word "kesinti" only appears in marketing text like "kesintisiz doğal gaz" (uninterrupted gas). The announcements are price tariffs and tenders.
- Result: Başkentgaz doesn't publish planned or fault outages on its site; there is nothing to read.
- Recorded responses were removed from the repo.

### İzmirgaz

- No robots.txt (404), no restrictions.
- TLS: same problem as Başkentgaz (the Sectigo DV R36 intermediate isn't sent); I connected the same way.
- Outage information is on the "Sokağımda Gaz Var mı?" (is there gas in my street) page (`/SokagimdaGazVarmi.php`). Its content is loaded from the `pages/islemler/SokagimdaGazVarmi.php` fragment. The fragment is a form: pick district, pick neighbourhood, pick street. When a street is picked, only the street code is sent with `POST gaz.php` and the answer is for that street. No captcha.
- Problem: there is no list. To see all outages we would have to query every street in İzmir one by one. İzmir has tens of thousands of streets; a 15 minute scan can't do that and it would put real load on the site. It doesn't fit the "be gentle with the sources" rule.
- Recorded responses were removed from the repo.

### Natural gas result

The decision was "use İGDAŞ data from İBB if allowed, otherwise whichever of Başkentgaz or İzmirgaz has cleaner data". İBB has no İGDAŞ outage data. Başkentgaz publishes no outages at all. İzmirgaz only offers per-street queries. None of the three gives us a gentle collector, so I didn't pick one. I updated the natural gas item in Phase 9 to "on hold until a source is found". Options are below.

## How many requests I sent

- First round: BEDAŞ 9, AYEDAŞ 5, İSKİ about 10 (I stopped at the WAF), İGDAŞ 2 (robots.txt), İBB 3 (robots.txt and the 2 API requests that went against robots.txt).
- Second round (all checked against robots.txt): İBB 7 (robots.txt, 4 pages, 2 XLSX files), Başkentgaz 7 (2 robots.txt, home page, JS bundle, 3 API calls), İzmirgaz 5 (robots.txt, home page, query page, form fragment, one JS file that returned 404 because I requested the wrong path). Two intermediate certificates were also downloaded from the CAs' own servers (GoDaddy, Sectigo).

## Skeleton

### What I set up

- `services/collector` and `services/api`: Spring Boot 4.1.1 (the latest stable release right now), Java 21, Maven. For now only `spring-boot-starter-webmvc`, actuator and the Prometheus registry. Liveness/readiness probes are on (`/actuator/health/liveness`, `/actuator/health/readiness`) and `/actuator/prometheus` is exposed. Each service has a test that checks these three endpoints over real HTTP.
- `frontend`: React 19 + Vite 8. For now just a title and a version/environment label in the corner (`VITE_APP_VERSION`, `VITE_APP_ENV` come from build args). nginx proxies `/api/` to the api service. Buffering is off for `/api/stream`; SSE will need that in Phase 3.
- `docker-compose.yml`: postgres 18, redis 8, collector, api, frontend. Each has a healthcheck and a `mem_limit`. Services don't start until their dependencies are healthy. The database user name and password come from a `.env` file. Only `.env.example` is in the repo; without `.env` compose stops with a clear error message.
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
- The first commits had a local Postgres password in `docker-compose.yml` and the README. Even if it's local only, CLAUDE.md says passwords never go into the repo. Before pushing I moved it to `.env` and rewrote the not-yet-pushed commits (the compose, fixture and README commits). At the same time I masked the reCAPTCHA site key in the AYEDAŞ fixture. A site key is public anyway, but "nothing that looks like a key is in the repo" is cleaner.

## Open decisions (yours)

1. The İSKİ data (İBB) is historical, newest record 2024-02-19. Should v1 show the water layer as it is, as "past outages"? Do you want me to draft an access request to İSKİ for live water data?
2. Do you approve scanning İSKİ (İBB) once a day? It's a deviation from the 5/15 minute rule in CLAUDE.md.
3. There is no suitable natural gas source. Options: (a) ask İGDAŞ or Başkentgaz for permission/data, (b) take gas out of v1.1 and put something else in (for example an electricity or water source from one of the v1.2 cities in the plan), (c) query İzmirgaz only for a few selected streets. My recommendation is to do (a) and (b) together.
