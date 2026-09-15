# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions: [Semantic Versioning](https://semver.org/). The "What's new" window in the app and the GitHub Release notes read the Turkish file, [CHANGELOG.md](CHANGELOG.md); this file is its translation.

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
