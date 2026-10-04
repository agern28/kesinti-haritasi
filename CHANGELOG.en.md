# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions: [Semantic Versioning](https://semver.org/). The "What's new" window in the app and the GitHub Release notes read the Turkish file, [CHANGELOG.md](CHANGELOG.md); this file is its translation.

## [1.0.4] - 2026-10-04

### Fixed
- A freshly started api pod could take traffic before its Redis Pub/Sub subscription was in place. Live events published in that window never reached that pod: connected browsers missed them and the map silently stayed stale, with no error anywhere because the request itself succeeded. The pod is now not considered ready until the subscription exists (`sseFanout` readiness check), so Kubernetes keeps it out of the Service until then. The race was caught by a test that failed in CI (23 September, `TransitionSweeperTest`).

### Note
- Version numbers run on a single line for all services: each service ships under its own tag (`api-vX.Y.Z`) but the number comes from the single sequence in this file. That is why the api went from 1.0.1 to 1.0.4; 1.0.2 and 1.0.3 were collector releases.

## [1.0.3] - 2026-10-04

### Fixed
- A province prefix joined by an underscore was not stripped: for multi-province companies CK Enerji writes planned outages as "BURDUR_KEMER". That was the single row still unmatched on the map after 1.0.2; the underscore now counts as a separator.

## [1.0.2] - 2026-10-04

### Fixed
- Some outages landed on no district at all on the map: when the name a source wrote into the district field did not match the district name in the boundary data, that outage was never coloured. 174 of the 19,285 rows in the database (0.9%) were in that state. All four causes are fixed:
  - Combined districts: records where AEDAŞ writes "KONYAALTI / KEPEZ" are split into two outages (46 rows).
  - A neighbourhood instead of a district: some BEDAŞ records carried a neighbourhood in the district field (Yenibosna, Zincirlikuyu, Kumburgaz, Beyazıt, Çağlayan, Kilyos, Kemerburgaz, Hadımköy; 110 rows). They now go to the right district, with the neighbourhood name added to the front of the neighbourhood list.
  - A province-name prefix: names like ÇEDAŞ's "SİVAS (MERKEZ)" and "TOKAT MERKEZ" lose the prefix (16 rows).
  - "MERKEZ" and "KIRSAL" become the province name, since the central district carries the province's name in the boundary data.

### Added
- `make map-match`: compares the district names in the database against the map boundary file and lists anything unmatched together with its source. It warns when a new source arrives or an existing one changes how it writes names.

## [1.0.1] - 2026-10-02

The app behaves the same; this release is about resilience and operations. It was cut so the images deployed to Kubernetes carry the fixes made after 1.0.0.

### Fixed
- When a source site's robots.txt was briefly unreachable, every source was treated as disallowed. A valid cached copy is now reused, and the log says what actually went wrong.
- On Kubernetes, requests from the UI to the api returned 502: nginx's resolver cannot resolve the short service name, it needs the fully qualified one.
- The district boundary file is served from a versioned URL, so a new boundary file is not shadowed by a week-old cached copy.

### Changed
- A request too deep into the pages (`page * size` above 50,000) now returns 400; it was a cheap way to put load on a public api.
- Two database indexes for the active outage list and the map summary.
- The Redis event stream length went from 100,000 to 30,000 based on the measured ~0.56 KB per event.
- Dependencies updated; Dependabot no longer proposes major version jumps.

## [1.0.0] - 2026-09-15

### Added
- Map of Turkish districts. Districts are coloured by the number and type of active outages.
- Type filter: electricity and water. The filter will open up once there is a natural gas source.
- Clicking a district lists its active and upcoming outages: neighbourhoods, times, source and a link to the announcement.
- Live updates: a new outage shows up on the map without a page reload, and the district flashes briefly.
- Data freshness: time of the last update and the last scan per source. A warning shows up if a source is delayed.
- Connection state: live or reconnecting.
- Sources: BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ (electricity), İZSU (water) and İSKİ historical data from İBB Open Data.
- İZSU fault records: water faults in İzmir are on the map too.

### Fixed
- KCETAŞ's outages in Gemerek (Sivas) were stored under Kayseri; they are in Sivas now.
- The İZSU scan failed at times with no maintenance or faults (at night).
- One of ÇEDAŞ's servers returned a list from 2024; outages in Sivas, Tokat and Yozgat dropped off the map and came back. The stale list is no longer accepted.
- The frontend lost its connection when the api was restarted.
