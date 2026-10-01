# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions: [Semantic Versioning](https://semver.org/). The "What's new" window in the app and the GitHub Release notes read the Turkish file, [CHANGELOG.md](CHANGELOG.md); this file is its translation.

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
