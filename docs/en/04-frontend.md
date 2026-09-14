# Phase 4 - Frontend

Date: 2026-09-14

Türkçe: [../tr/04-frontend.md](../tr/04-frontend.md)

The map works. Districts are coloured by the number and type of active outages, and clicking a district lists its outages. When a new outage lands in the database it shows up on the map without a page reload.

## What's there

| Part | What it does |
|---|---|
| Map | Leaflet with OpenStreetMap tiles. 973 district polygons, province borders as thicker lines. Hovering a district shows its name and the outage count for the selected types. |
| Colour | Hue shows the type, darkness shows the count (1, 2-4, 5-9, 10+). Electricity is orange, water blue, purple if both types are present. The gas hue (red) is ready, there is no source yet. |
| Type filter | Electricity and water. Gas is visible but can't be selected (its source comes in Phase 9). The filter affects both the colouring and the panel. |
| District panel | "Ongoing" and "Upcoming": type, planned/fault, time range (Turkey time), neighbourhoods, reason, source and a link to the announcement. On narrow screens it is a bottom sheet. |
| Live updates | `/api/stream` (SSE). When an event arrives the summary is fetched again and the district of an outage that became active flashes. If the event is for the district open in the panel, the list reloads. |
| Data freshness | "Son güncelleme: 2 dk önce" (last update: 2 min ago), i.e. the most recent successful scan across sources. Clicking it shows the last scan per source. If a source is delayed, the top bar says "N kaynak gecikmeli" (N sources delayed). |
| Connection state | Green "Canlı" (live), blinking amber "Yeniden bağlanıyor" (reconnecting). |
| Version and What's new | `v0.1.0 · LOCAL` bottom left (build args `APP_VERSION`, `APP_ENV`). Clicking it opens the "Yenilikler" (what's new) window, whose content is `CHANGELOG.md` at the repo root. |

## District boundaries

There were two candidates:
- **geoBoundaries TUR ADM2**: from OpenStreetMap, licence ODbL 1.0. The data is from 2023 and districts carry no province. ODbL requires derived data to be shared under the same licence.
- **OCHA HDX COD-AB** ("Türkiye - Subnational Administrative Boundaries"): data from the General Command of Mapping (HGK), licence CC BY-IGO. 973 districts, each with its province name, updated up to 2025.

I picked HDX. CC BY-IGO requires attribution and a note if the data was changed. The map corner says "İlçe sınırları: HGK / OCHA (CC BY-IGO)". The simplification and the name fixes are described in these notes and in [`frontend/public/geo/NOTICE.txt`](../../frontend/public/geo/NOTICE.txt) next to the file.

The Turkish names in HDX turned out to be broken. The letter "ı" has become "i" (Kadiköy, Ağri), and some "i"s are followed by a separate combining dot (U+0307) (Şi̇şli̇). The matching key isn't affected, but the names shown on screen would be wrong. I took the correct spelling from Wikidata (CC0), matched by key:
- 967 district names come from Wikidata.
- Yahyalı and Muratlı aren't listed under their province on Wikidata, and HDX lost the "ı" in both. These two are written by hand in the script.
- For the remaining 4 districts (Kahramankazan, Elbistan, Uşak, Ereğli) the HDX name is used with the combining dot removed. They have no "ı" problem.

The file is produced by [`frontend/scripts/ilceler.py`](../../frontend/scripts/ilceler.py). The script downloads HDX and Wikidata, fixes the names, simplifies to 6% with mapshaper and writes TopoJSON. The 29 MB GeoJSON goes down to 476 KB, 182 KB with gzip (gzip is now on in nginx). Province borders aren't a separate file: in TopoJSON neighbouring districts share the same line, and lines with a different province on each side are drawn as province borders.

## Matching source names to the map

The API gives `ilKey`/`ilceKey` with every outage (Phase 3). The map id is `IL|ILCE`, with a rule for two differences:
- Sources write the central district of a province as `MERKEZ` (Burdur, Isparta, Sivas). In HDX the central district carries the province name. `MERKEZ` is mapped to the province name.
- HDX writes Gaziosmanpaşa as two words ("Gazi Osmanpaşa"). Spaces are removed from the key.

I checked this against live data: 137 of the 139 province/district pairs from the sources matched. The other two:
- **A free-text ÇEDAŞ record** came in with province `TOKAT.` and district `.`. There is nothing to do on the map side. The legend counts it as "Haritada yeri bulunamayan: 1 kesinti" (1 outage with no place on the map), and hovering shows the name. The outage isn't lost, but it has no place on the map either.
- **KCETAŞ's Gemerek records were stored under Kayseri.** Gemerek is in Sivas, and KCETAŞ serves it too. The province was hard-coded as `KAYSERİ` in the collector. The source writes the province at the end of the address (`... KÖPRÜBAŞI MAH. GEMEREK SİVAS`), and the parser now reads it from there: the single word after the district, otherwise Kayseri. Two tests were added and all 78 collector tests are green. Live, the 4 old Gemerek records went GONE, the same records came in as NEW with `SİVAS`, and the map summary shows Sivas/Gemerek. Details: [02-collector.md](02-collector.md).

## Live updates

- When the connection drops, `EventSource` reconnects by itself and sends Last-Event-ID. But if the server answers with anything other than 200 (nginx returns 502 while the api is down), it closes the connection for good. So `src/lib/live.js` reopens the connection itself in that case. The wait starts at 2 s and doubles on every attempt, up to 30 s. The last event id goes along as `?lastEventId=` (the api already accepted this in Phase 3), and the missed events arrive in order.
- Events are collected for 300 ms and handled in one go. On the initial load or when a scan finishes, dozens of events arrive back to back, and doing the work for each of them separately is pointless.
- The event body is the full outage. But instead of computing the summary from events, `/api/map/summary` is fetched again (cached in Redis, cheap). That way the client and the server can't drift apart. In case an event is missed, the summary is also fetched every 2 minutes.
- Highlight: the district of an outage that becomes active flashes three times with a red outline. Becoming active can mean a new outage or a planned outage whose start time has come (`outage.updated` from the `TransitionSweeper`). An ended outage doesn't flash, the colour just fades. With `prefers-reduced-motion` it is a single, slow flash.
- After a reconnect and on `outage.resync`, the summary, the sources and the open panel are refreshed.

## CHANGELOG and the build

The "Yenilikler" window reads from the CHANGELOG. CHANGELOG.md is really a Phase 5 item, but for the window I already added `CHANGELOG.md` (Turkish, the window reads this one) and `CHANGELOG.en.md` at the repo root. The format is Keep a Changelog, and for now there is a single section, "Yayınlanmamış" (unreleased). Version numbers will start with v1.0.0 in Phase 5.

The window opens by itself if the last version seen in this browser (localStorage) differs from the current one. That's the "new version on PROD and the What's new window opens" step of the demo scenario in the plan. It also opens on the first visit.

The frontend image reads `CHANGELOG.md` at build time. So in compose the build context changed from `./frontend` to the repo root (`dockerfile: frontend/Dockerfile`). Only `frontend/` and `CHANGELOG.md` are sent to Docker (`frontend/Dockerfile.dockerignore`). CI in Phase 5 should use the same context.

## Why this way

- **No react-leaflet**: I used Leaflet directly. The map is set up once, and the only thing that changes afterwards is the style of the 973 polygons and the highlight. Doing that with refs and `setStyle` is simpler than react-leaflet's component model.
- **No Markdown library**: only version headings, group headings and bullet lines are read from the CHANGELOG, and a 40-line parser was enough. `innerHTML` isn't used.
- **TopoJSON**: the shared border of neighbouring districts is stored once, so the file is smaller. Province borders come out without extra data. `topojson-client` is a small library.
- **Tile server**: OpenStreetMap's tile server. Its usage policy allows small projects, and the attribution is in the corner. With real traffic we may need another provider or our own tile server. The k6 test in Phase 9 measures the api, not the tiles.
- **Times in Turkey time**: even if the browser is in another time zone, times are shown in `Europe/Istanbul`.

## Tests

`npm test` (Vitest, jsdom, Testing Library): 34 tests, all green. `npm run build` is green too (JS 391 KB, 121 KB with gzip).
- District id, the `MERKEZ` and space rules, the same name key as the api (Turkish upper case, U+0307)
- Colour levels and the hue per type
- Relative time and time ranges in Turkey time
- CHANGELOG parsing
- The SSE connection:
  - passes events on
  - doesn't interfere with the browser's own reconnect
  - reopens with the last id after a permanent drop, with a growing wait
  - doesn't retry once stopped
- Components:
  - connection badge
  - data freshness and a delayed source
  - type filter
  - the unmatched outage count in the legend
  - district panel: active and upcoming outages, announcement link, queries sent to the api, count outside the filter, reload
  - when the What's new window opens

Leaflet itself isn't tested in jsdom. I checked the map with a browser in compose.

## Hands-on check (compose, live sources)

I ran the stack against the live sources and checked the UI with headless Chromium (Playwright, a script outside the repo).
- 973 districts were drawn, 57 of them coloured at that moment. On the first visit the What's new window opened, and after closing it didn't open again.
- Clicking Pınarbaşı opened the panel: no ongoing outage, 27 upcoming KCETAŞ outages, each with neighbourhood, time and announcement link.
- **Highlight without a reload**: I wrote a fake active water outage for İzmir/Bornova to the Redis Stream. Bornova started flashing after 511 ms and turned light blue after 545 ms. After writing GONE, the colour was gone after 528 ms. The page never reloaded. These times include writing the event with `docker compose exec` and the 300 ms batching.
- **With the API stopped**: after `docker compose stop api` the badge turned to "Yeniden bağlanıyor". Most of the measured 10.5 s was waiting for the api to shut down. After `docker compose start api` it was back to "Canlı" in 8.7 s. The only console errors were the 502s in between and the cut SSE stream.
- **Mobile (390 px)**: no horizontal scroll, the panel opens from the bottom, and the selected district is fitted into the area above the panel.

## Where I got stuck

- **White lines between tiles**: with `zoomSnap: 0.25` Turkey fit the screen exactly, but fractional zoom left thin gaps between the tiles. I went back to whole zoom levels. On narrow screens Turkey overflows the edges a little.
- **On mobile the attribution covered the version badge**: on a narrow screen the attribution wraps to two lines. The version badge and the legend were moved above it.
- **On mobile the selected district ended up under the panel**: the panel covers the bottom 60% of the map. On narrow screens the selected district is now fitted into the area above it.
- **A typo in the CSS broke the build**: a line `max width:` was left in. The tests passed and Vite's CSS minifier failed.
- **The first Wikidata query came up short**: querying districts by type (P31) missed 612 districts, because district types on Wikidata aren't consistent. Querying the subdivisions of provinces (P150) brought it down to 6.

## Known limitations

- The ÇEDAŞ record with a broken name isn't on the map, it is only counted in the legend. The free-text format of ÇEDAŞ is still "best effort" in the collector.
- İSKİ data is historical. It shows neither on the map nor in the panel, because the panel only shows ongoing and upcoming outages. Right now the "water" layer only has İZSU. The place for İSKİ's data is the neighbourhood report card in v2.1.
- The map is at district level, there are no neighbourhood polygons (the plan left that for later).
- Tiles come from OpenStreetMap. With heavy traffic we'll need another provider.
- The browser check isn't in the repo or in CI. It could go into CI in Phase 5.
- On narrow screens (390 px) Turkey at zoom 5 overflows the edges a little.
- On desktop the tile lines are gone with whole zoom levels. In headless Chromium's mobile view at 2x pixel density there are still thin lines at tile edges. I haven't tried it on a real phone.
