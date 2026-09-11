# Source survey: electricity distribution companies and water utilities

Date: 2026-09-11. Third discovery round of Phase 1. The first two rounds: [01-discovery-and-skeleton.md](01-discovery-and-skeleton.md).

Türkçe: [../tr/01-kaynak-taramasi.md](../tr/01-kaynak-taramasi.md)

Goal: find electricity sources that could join BEDAŞ in v1, find metropolitan water utilities that publish live data, and propose a source to replace natural gas in v1.1.

## Short version

- **v1 electricity proposal:** BEDAŞ + AEDAŞ + ÇEDAŞ + KCETAŞ. AEDAŞ and ÇEDAŞ run on the same CK Enerji platform as BEDAŞ, so the parser is the same. KCETAŞ returns GeoJSON with one request per date.
- **v1 live water proposal:** İZSU (İzmir). The table is rendered on the server and the fields are clean.
- **Instead of natural gas in v1.1:** ASKİ (Ankara water faults). Backups: BUSKİ and MESKİ.
- **Removed:** sources that block with robots.txt, a WAF or a captcha are off the list and won't get any more requests. Sources that timed out won't be retried either, to be safe. The list is below.

These proposals are awaiting approval. Phase 2 won't start without it.

## How I surveyed

Every request went through the same helper (`polite.py`, not in the repo, discovery only):

- Before every request the host's robots.txt was read and interpreted per RFC 9309: 4xx means no restrictions, 5xx or unreachable means fully disallowed. Matching supports `*` and `$`, and the longest rule wins.
- Redirects were followed by hand. At every hop the target host's robots.txt was checked again.
- At least 3 seconds between two requests to the same host. If `Crawl-delay` was longer, that was used.
- User-Agent: `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`.
- Some servers don't send their intermediate certificate (BUSKİ, MESKİ, TREDAŞ, Başkentgaz, İzmirgaz). I didn't turn TLS verification off. The intermediate was fetched from the AIA URL in the certificate, which is the CA's own server.
- Three steps per source: robots.txt, home page, and the outage link found on the home page. Promising sources got 1-3 extra requests to see the data endpoint.

### The DNS problem and DoH

Near the end of the survey, DNS stopped working entirely inside WSL. It was a WSL problem, not a site blocking us:

- Inside WSL no name resolved at all, `github.com` included.
- At the same time the same names resolved fine on the Windows side.
- A direct TCP connection from WSL to 1.1.1.1 worked.

The broken piece was WSL's DNS proxy (`172.28.64.1`), which didn't answer. To avoid touching system settings, I added a fallback to the helper: when the system resolver fails, the name is resolved through Cloudflare DNS-over-HTTPS (1.1.1.1). That only changes how the IP is found. The connection and TLS verification still use the real host name.

Requests attempted during the DNS outage never reached a server and are not in the counts. Three hosts had been wrongly cached as "disallowed" in the robots cache. I cleared them and fixed the helper so temporary errors are never cached again.

For the WSL DNS, restarting WSL with `wsl --shutdown` usually fixes it. It doesn't affect the project itself, since the collector will run on k3s.

## Electricity distribution companies (21)

Regions are the EPDK distribution regions. Request counts include robots.txt requests.

| Company | Region | robots.txt | Outage data | Format | Difficulty | Req. | Status |
|---|---|---|---|---|---|---|---|
| BEDAŞ | Istanbul, European side | none | `GET /GetItemsData` (planned), CK API `RetrieveOutages` (faults) | JSON | Neighbourhoods inside the message text; fault location via an extra call per transformer | 9 | v1 |
| AEDAŞ (Akdeniz) | Antalya, Burdur, Isparta | none | `GET www.akdenizedas.com.tr/GetItemsData` (225 records), `kesintiapi.ckenerji.com.tr/AEDAS/RetrieveOutages` (249 rows, 6 outages) | JSON | Same as BEDAŞ | 8 | **Proposed** |
| ÇEDAŞ (Çamlıbel) | Sivas, Tokat, Yozgat | none | `GET www.cedas.com.tr/GetItemsData` (86 records), CK API `CEDAS/RetrieveOutages` (empty list at the time) | JSON | Same as BEDAŞ. The certificate of `kesinti.cedas.com.tr` has expired; that host isn't used | 6 | **Proposed** |
| KCETAŞ | Kayseri region | none | `/tr/planli-kesintiler-bakimlar` server-rendered table, `POST /kesinti-sorgu.php` (`bakim_tarih=YYYY-MM-DD`) | HTML table, GeoJSON | No source id, dedup by hash | 4 | **Proposed** |
| Çoruh EDAŞ | Artvin, Giresun, Gümüşhane, Rize, Trabzon | present, only `.xls/.xlsx` disallowed | `GET /BilgiDanisma/GetKesintiler?yil=&ay=&il=` | HTML table rows | One request per province (5), planned only | 4 | Fine, backup |
| Fırat EDAŞ | Bingöl, Elazığ, Malatya, Tunceli | present, only `.xls/.xlsx` disallowed | Same platform as Çoruh | HTML table rows | One request per province (4), planned only | 4 | Fine, backup |
| UEDAŞ | Bursa, Balıkesir, Çanakkale, Yalova | allowed | `POST /planli-kesintiler/sec.asp` (`ilce=<id>`) | HTML rows inside XML | One request per district, about 50 districts | 4 | Partly fine, heavy |
| GDZ | İzmir, Manisa | present, only file extensions disallowed | Planned work map (Vue). `app.js` has JSON endpoints like `/api/outages-v2`, `/api/unplanned-outages` | JSON (guess) | Endpoints not tried | 4 | Probably fine, not verified |
| ADM | Aydın, Denizli, Muğla | allowed | Same platform as GDZ | JSON (guess) | Endpoints not tried | 3 | Probably fine, not verified |
| TREDAŞ | Edirne, Kırklareli, Tekirdağ | none | `tredas.com.tr/api/kesintiler/list` | JSON | Needs a district code ("Ilce Kodu Bos Olamaz"). The page has reCAPTCHA; unclear whether the API requires it | 4 | Unclear |
| Göksu (Akedaş) | Kahramanmaraş, Adıyaman | none | Angular SPA, no outage endpoint found in the bundle | - | No data found | 3 | No data |
| DEDAŞ | Diyarbakır, Şanlıurfa, Mardin, Batman, Siirt, Şırnak | main site unrestricted | `api.dedas.com.tr/api/interruptions/getplannedqutages` (POST JSON) | JSON | The API host's robots.txt timed out, treated as disallowed, no data request sent | 4 | Unreachable |
| VEDAŞ | Van, Bitlis, Muş, Hakkari | none | Home page always arrived truncated and compressed; a `curl` attempt timed out | - | - | 4 | Unreachable |
| MERAM | Konya, Aksaray, Karaman, Kırşehir, Nevşehir, Niğde | unrestricted | Planned outages in an iframe on `cc.meramedas.com.tr` | - | The iframe host's robots.txt timed out | 5 | Unreachable |
| YEDAŞ | Samsun, Ordu, Çorum, Amasya, Sinop | none | Next.js map, `/api/planli-kesinti-harita` | JSON (guess) | Request timed out | 4 | Unreachable |
| Aras EDAŞ | Erzurum, Ağrı, Kars, Iğdır, Ardahan, Erzincan, Bayburt | `Disallow: /api/`, `/_next/` | The page shell is allowed, the data comes through disallowed paths | - | robots.txt | 5 | **Blocked** |
| AYEDAŞ | Istanbul, Asian side | allowed | Enerjisa online query form | - | reCAPTCHA | 5 | **Blocked** |
| Toroslar EDAŞ | Adana, Gaziantep, Hatay, Kilis, Mersin, Osmaniye | allowed | Same form as AYEDAŞ | - | reCAPTCHA | 4 | **Blocked** |
| Başkent EDAŞ | Ankara, Zonguldak, Karabük, Bartın, Çankırı, Kastamonu, Kırıkkale | allowed | Same form as AYEDAŞ | - | reCAPTCHA | 4 | **Blocked** |
| OEDAŞ | Eskişehir, Afyonkarahisar, Bilecik, Kütahya, Uşak | allowed | ASP.NET form (province/district) | - | reCAPTCHA | 5 | **Blocked** |
| SEDAŞ | Sakarya, Kocaeli, Bolu, Düzce | unrestricted | `/Tr/SupplyContinuityUrl/PlannedView` form | - | reCAPTCHA | 4 | **Blocked** |

### Why these three

- **AEDAŞ and ÇEDAŞ:** they are the BEDAŞ collector with the host and the source code (`BEDAS`, `AEDAS`, `CEDAS`) as parameters. Per scan: 1 request for planned outages, 1 for faults, plus a few location requests for transformers seen for the first time (cached). Three regions come almost for free.
- **KCETAŞ:** one POST per date is enough for today and the next few days. The answer is GeoJSON: district, address (neighbourhood), type (`Bildirimli`), start, end and a polygon. The polygon is ready data for going down to neighbourhood level later. Having a different parser also shows the "new source = new class + test" idea.
- Çoruh and Fırat are good too (one parser, 9 provinces), but they only give planned outages and need one request per province. They are next in line for v1.2.

Sample records:

```
AEDAŞ GetItemsData: {"id": "10624928", "plannedOutage": {"reason": "Bakım Çalışması", "city": "ANTALYA", "county": "ELMALI",
  "startDateTime": "2026-09-10 09:00:00", "endDateTime": "2026-09-10 16:00:00", "message": "...", "lat": "...", "lon": "..."}}
KCETAŞ kesinti-sorgu.php: {"type": "Feature", "properties": {"ilce": "PINARBAŞI", "adres": " SOLAKLAR MAH. PINARBAŞI KAYSERİ",
  "tur": "Bildirimli", "baslangic": "2026-09-11T09:00:00", "bitis": "2026-09-11T17:00:00"}, "geometry": {"type": "Polygon", ...}}
Çoruh GetKesintiler: ARTVİN | YUSUFELI | 17.09.2026 | 09:30 | 16:00 | İşletme Bakım Çalışması (Ekip) | BADEMKAYA KÖYÜ KIRAVET MEVKİİ | 11.09.2026
UEDAŞ sec.asp: <kesinti><metinTablo>&lt;tr&gt;&lt;td&gt;15 Eylül Salı&lt;/td&gt;&lt;td&gt;09:00-13:00&lt;/td&gt;&lt;td&gt;SDK DEPLASE&lt;/td&gt;...
```

## Metropolitan water utilities (10)

| Utility | robots.txt | Outage data | Format | Difficulty | Req. | Status |
|---|---|---|---|---|---|---|
| İZSU (İzmir) | `Disallow: /api/`, `/icons/` | `/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama`, server-rendered table | HTML table: district, neighbourhoods, job, start, end, description | Low. The API is disallowed but the data is in the page | 5 | **Proposed (v1)** |
| ASKİ (Ankara) | unrestricted | `/TR/Kesinti.aspx`, server-rendered list | HTML: district, fault/planned, fault time, repair time, detail text | Neighbourhoods inside the detail text | 4 | **Proposed (v1.1)** |
| BUSKİ (Bursa) | none | `/gunluk-su-kesintileri`, GeoJSON embedded in the page | GeoJSON (EPSG:2320): outage no, district, neighbourhood, description, planned start/end, status | Coordinates need converting to WGS84 | 3 | Fine, backup |
| MESKİ (Mersin) | none | `online.meski.gov.tr/meta/subscription/interruptions`, JSON embedded in the page | JSON: districts and neighbourhoods, start, end, cause, active | Low | 4 | Fine, backup |
| ASAT (Antalya) | none | `kesinti.asat.gov.tr/dbo_kesintiListe/list` (PHPRunner) | HTML list | No records at scan time, fields not seen | 5 | Unclear |
| KASKİ (Kayseri) | unrestricted | `/su-kesintileri` table | HTML table: date, district, neighbourhood, type, hours, description | The table was empty at scan time | 3 | Probably fine |
| KOSKİ (Konya) | unrestricted | `/koski/ariza-ve-kesintiler` | - | No data found in the page | 3 | Unclear |
| ESKİ (Erzurum) | allowed | Outages as individual news pages | HTML text | No structured list. Note: `eski.gov.tr` turned out to be Erzurum's utility; Eskişehir wasn't checked | 3 | Hard |
| İSU (Kocaeli) | unrestricted | No outage page found (only fault reporting) | - | - | 2 | No data |
| GASKİ (Gaziantep) | unrestricted | No outage page found | - | - | 4 | No data |

Sample records:

```
İZSU: BAYRAKLI | ALPASLAN, BAYRAKLI, ÇİÇEK, FUAT EDİP BAKSI | Deplase çalışması | 10.09.2026 - 22:00 | 11.09.2026 - 06:00 | ...
ASKİ: MAMAK | Arıza Kaynaklı | Arıza Tarihi: 11.09.2026 13:00:00 | Tamir Tarihi: 11.09.2026 23:00:00 | Detay: PLANSIZ SU KESİNTİSİ: ...
BUSKİ: {"SU_KESINTI_NO": 2186, "ILCE_ADI": "NİLÜFER", "MAHALLE_ADI": "IŞIKTEPE", "PLANLANAN_BASLANGIC_TARIHI": "11/09/2026 12:15",
  "PLANLANAN_BITIS_TARIHI": "11/09/2026 13:15", "DURUM": "Kesildi"}
MESKİ: {"districtList": [{"district": "BOZYAZI", "quarterList": [{"quarter": "AKCAMİ"}, {"quarter": "NARİNCE"}]}],
  "startDate": "2026-09-11T10:11:00.000", "finishDate": "2026-09-11T15:00:00.000", "isActive": 1, "cause": "..."}
```

### Water proposals

- **v1 live water: İZSU.** One request gives district, neighbourhood list, start and end in separate columns. It's the easiest to normalize. robots.txt disallows `/api/`, and we don't use the API, only the allowed page.
- **v1.1 (instead of natural gas): ASKİ.** Ankara is a big city and the data is live; fault outages are listed right away. Neighbourhoods have to be pulled out of the detail text, similar to the message parsing for BEDAŞ.
- **Backups:** BUSKİ (has an outage number that can be the `external_id`, and polygons) and MESKİ (structured JSON).

## Removed sources and blocklist

As decided, these won't get any more requests. They won't appear in the collector's configuration either.

| Source / host | Reason |
|---|---|
| `www.igdas.istanbul`, `www.igdas.com.tr` (İGDAŞ) | robots.txt `Disallow: /` |
| `harita.iski.gov.tr` | WAF "Request Rejected" |
| `iskiapi.iski.istanbul` | Needs an embedded token, not used |
| `data.ibb.gov.tr/api/` (this path only) | robots.txt `Disallow: /api/`. Dataset pages and download links are allowed |
| `arasedas.com` `/api/`, `/_next/` | robots.txt |
| `online.ayedas.com.tr`, `online.toroslaredas.com.tr`, `online.baskentedas.com.tr` | reCAPTCHA |
| `www.osmangaziedas.com.tr` planned outage form | reCAPTCHA |
| `www.sedas.com` PlannedView | reCAPTCHA |
| `api.dedas.com.tr`, `www.vedas.com.tr`, `cc.meramedas.com.tr`, `www.yedas.com/api/` | Timeouts. Not clear whether they block us; not retried, to be safe |

## Request counts

Requests sent per source during discovery. robots.txt requests are included. Attempts that never left the machine because of the DNS outage are not. Intermediate certificate downloads (CA servers) and DoH queries (1.1.1.1) aren't counted, since they aren't source sites.

| Source | Req. | | Source | Req. |
|---|---|---|---|---|
| BEDAŞ | 9 | | İSKİ (iski.istanbul, iskiapi, harita) | ~10 |
| AYEDAŞ | 5 | | İBB Open Data | 10 |
| AEDAŞ | 8 | | İGDAŞ (robots.txt only) | 2 |
| ÇEDAŞ | 6 | | Başkentgaz | 7 |
| CK Enerji API robots.txt (shared) | 1 | | İzmirgaz | 5 |
| KCETAŞ | 4 | | ASKİ | 4 |
| Çoruh EDAŞ | 4 | | İZSU | 5 |
| Fırat EDAŞ | 4 | | BUSKİ | 3 |
| UEDAŞ | 4 | | MESKİ | 4 |
| GDZ | 4 | | ASAT | 5 |
| ADM | 3 | | KASKİ | 3 |
| TREDAŞ | 4 | | KOSKİ | 3 |
| Göksu (Akedaş) | 3 | | ESKİ (Erzurum) | 3 |
| DEDAŞ | 4 | | İSU | 2 |
| VEDAŞ | 4 | | GASKİ | 4 |
| MERAM | 5 | | | |
| YEDAŞ | 4 | | | |
| Aras EDAŞ | 5 | | | |
| Toroslar EDAŞ | 4 | | | |
| Başkent EDAŞ | 4 | | | |
| OEDAŞ | 5 | | | |
| SEDAŞ | 4 | | | |

About 168 requests in total, spread over 35 sources. The most requests went to İSKİ and İBB in the first round, not to promising sources like ASKİ or AEDAŞ. No source got more than 10.

## Fixtures

Only the fixtures of the electricity sources proposed for v1 are kept in the repo (`services/collector/src/test/resources/fixtures/`):

- `bedas/` (from Phase 1)
- `aedas/planned-getitemsdata.json`, `aedas/unplanned-retrieve-outages.json`
- `cedas/planned-getitemsdata.json`, `cedas/unplanned-retrieve-outages.json` (empty list at the time)
- `kcetas/planli-kesintiler-bakimlar.html`, `kcetas/kesinti-sorgu-2026-09-11.json`

One exception: `iski/ibb-su-kesintileri-2023-2024.xlsx` also stays in the repo. The daily İBB scan is approved and the Phase 2 parser test needs this file. If you don't want it, I'll remove it and it can be downloaded again with one request in Phase 2.

The responses from the other sources aren't in the repo; what I saw is written down in this document. If İZSU and ASKİ are approved, their fixtures will be fetched in Phase 2 together with the parser tests.
