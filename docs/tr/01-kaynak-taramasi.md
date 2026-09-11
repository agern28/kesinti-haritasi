# Kaynak taraması: elektrik dağıtım şirketleri ve su idareleri

Tarih: 2026-09-11. Faz 1'in üçüncü keşif turu. İlk iki tur: [01-kesif-ve-iskelet.md](01-kesif-ve-iskelet.md).

English: [../en/01-source-survey.md](../en/01-source-survey.md)

Amaç: BEDAŞ'a ek olarak v1'e girebilecek elektrik kaynaklarını bulmak, canlı su verisi veren büyükşehir su idarelerini bulmak ve v1.1'de doğalgazın yerine geçebilecek bir kaynak önermek.

## Kısa sonuç

- **v1 elektrik önerisi:** BEDAŞ + AEDAŞ + ÇEDAŞ + KCETAŞ. AEDAŞ ile ÇEDAŞ BEDAŞ'la aynı CK Enerji altyapısını kullanıyor, parser aynı. KCETAŞ tarih başına tek istekte GeoJSON dönüyor.
- **v1 canlı su önerisi:** İZSU (İzmir). Tablo sunucuda render ediliyor, alanlar düzgün.
- **v1.1'de doğalgaz yerine:** ASKİ (Ankara su arızaları). Yedek adaylar: BUSKİ ve MESKİ.
- **Çıkarılanlar:** Robots.txt, WAF veya captcha ile engelleyen kaynaklar listeden çıkarıldı, onlara bir daha istek atılmayacak. Zaman aşımına uğrayanlara da temkinli davranıp tekrar istek atılmayacak. Liste aşağıda.

Bu öneriler onay bekliyor. Faz 2'ye onay gelmeden başlanmayacak.

## Nasıl taradım

Her istek aynı yardımcıdan geçti (`polite.py`, repoda değil, sadece keşif için):

- Her istekten önce host'un robots.txt'i okundu ve RFC 9309'a göre yorumlandı: 4xx kısıt yok, 5xx ya da erişilemedi tamamen yasak demek. Eşleşmede `*` ve `$` desteklendi, en uzun kural kazandı.
- Yönlendirmeler elle takip edildi. Her adımda hedef host'un robots.txt'i yeniden kontrol edildi.
- Aynı host'a iki istek arasında en az 3 saniye beklendi. `Crawl-delay` daha uzunsa ona uyuldu.
- User-Agent: `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`.
- Bazı sunucular ara sertifikayı göndermiyor (BUSKİ, MESKİ, TREDAŞ, Başkentgaz, İzmirgaz). TLS doğrulamasını kapatmadım. Ara sertifikayı sertifikadaki AIA adresinden, yani CA'nın kendi sunucusundan aldım.
- Kaynak başına üç adım: robots.txt, ana sayfa ve ana sayfadaki kesinti linki. Umut veren kaynaklara, veri endpoint'ini görmek için 1-3 ek istek atıldı.

### DNS sorunu ve DoH

Taramanın sonuna doğru WSL içinde DNS tamamen çalışmaz hale geldi. Bu bir site engellemesi değildi, WSL'in kendi sorunuydu:

- WSL'de `github.com` dahil hiçbir alan adı çözülmüyordu.
- Aynı anda Windows tarafında aynı alan adları sorunsuz çözüldü.
- WSL'den 1.1.1.1'e doğrudan TCP bağlantısı çalıştı.

Sorunlu olan, WSL'in DNS vekiliydi (`172.28.64.1`) ve cevap vermiyordu. Sistem ayarlarına dokunmamak için yardımcıya bir yedek ekledim: sistem çözümleyicisi hata verirse alan adı Cloudflare DNS-over-HTTPS (1.1.1.1) ile çözülüyor. Bu sadece IP bulma adımını değiştiriyor. Bağlantı ve TLS doğrulaması gerçek alan adıyla yapılıyor.

DNS arızası sırasında denenen istekler sunucuya hiç ulaşmadı, sayımlara da girmedi. Robots cache'ine yanlışlıkla "yasak" diye yazılan üç host'u temizledim ve yardımcıyı, geçici hataları bir daha cache'e yazmayacak şekilde düzelttim.

WSL'deki DNS için `wsl --shutdown` ile WSL'i yeniden başlatmak genelde yetiyor. Projenin kendisini etkilemiyor, çünkü collector k3s'te çalışacak.

## Elektrik dağıtım şirketleri (21)

Bölgeler EPDK dağıtım bölgeleri. İstek sayısına robots.txt istekleri dahil.

| Şirket | Bölge | robots.txt | Kesinti verisi | Format | Zorluk | İstek | Durum |
|---|---|---|---|---|---|---|---|
| BEDAŞ | İstanbul Avrupa yakası | yok | `GET /GetItemsData` (planlı), CK API `RetrieveOutages` (arıza) | JSON | Mahalle mesaj metninde, arızada konum trafo numarasından ek çağrıyla | 9 | v1 |
| AEDAŞ (Akdeniz) | Antalya, Burdur, Isparta | yok | `GET www.akdenizedas.com.tr/GetItemsData` (225 kayıt), `kesintiapi.ckenerji.com.tr/AEDAS/RetrieveOutages` (249 satır, 6 kesinti) | JSON | BEDAŞ ile aynı | 8 | **Öneri** |
| ÇEDAŞ (Çamlıbel) | Sivas, Tokat, Yozgat | yok | `GET www.cedas.com.tr/GetItemsData` (86 kayıt), CK API `CEDAS/RetrieveOutages` (o an boş liste) | JSON | BEDAŞ ile aynı. `kesinti.cedas.com.tr`'nin sertifikası süresi dolmuş, o host kullanılmıyor | 6 | **Öneri** |
| KCETAŞ | Kayseri ve civarı | yok | `/tr/planli-kesintiler-bakimlar` sunucuda render edilen tablo, `POST /kesinti-sorgu.php` (`bakim_tarih=YYYY-MM-DD`) | HTML tablo, GeoJSON | Kaynak id'si yok, tekilleştirme hash ile | 4 | **Öneri** |
| Çoruh EDAŞ | Artvin, Giresun, Gümüşhane, Rize, Trabzon | var, sadece `.xls/.xlsx` yasak | `GET /BilgiDanisma/GetKesintiler?yil=&ay=&il=` | HTML tablo satırları | İl başına 1 istek (5 il), sadece planlı | 4 | Uygun, yedek |
| Fırat EDAŞ | Bingöl, Elazığ, Malatya, Tunceli | var, sadece `.xls/.xlsx` yasak | Çoruh ile aynı altyapı | HTML tablo satırları | İl başına 1 istek (4 il), sadece planlı | 4 | Uygun, yedek |
| UEDAŞ | Bursa, Balıkesir, Çanakkale, Yalova | izinli | `POST /planli-kesintiler/sec.asp` (`ilce=<id>`) | XML içinde HTML satırlar | İlçe başına 1 istek, yaklaşık 50 ilçe | 4 | Kısmen uygun, ağır |
| GDZ | İzmir, Manisa | var, sadece dosya uzantıları yasak | Planlı çalışma haritası (Vue). `app.js`'te `/api/outages-v2`, `/api/unplanned-outages` gibi JSON endpoint'leri | JSON (tahmin) | Endpoint'ler denenmedi | 4 | Muhtemelen uygun, doğrulanmadı |
| ADM | Aydın, Denizli, Muğla | izinli | GDZ ile aynı altyapı | JSON (tahmin) | Endpoint'ler denenmedi | 3 | Muhtemelen uygun, doğrulanmadı |
| TREDAŞ | Edirne, Kırklareli, Tekirdağ | yok | `tredas.com.tr/api/kesintiler/list` | JSON | İlçe kodu istiyor ("Ilce Kodu Bos Olamaz"). Sayfada reCAPTCHA var, API'nin captcha isteyip istemediği belli değil | 4 | Belirsiz |
| Göksu (Akedaş) | Kahramanmaraş, Adıyaman | yok | Angular SPA, bundle'da kesinti endpoint'i bulunamadı | - | Veri bulunamadı | 3 | Veri yok |
| DEDAŞ | Diyarbakır, Şanlıurfa, Mardin, Batman, Siirt, Şırnak | ana site kısıt yok | `api.dedas.com.tr/api/interruptions/getplannedqutages` (POST JSON) | JSON | API host'unun robots.txt'i zaman aşımına uğradı, yasak sayıldı, veri isteği atılmadı | 4 | Erişilemedi |
| VEDAŞ | Van, Bitlis, Muş, Hakkari | yok | Ana sayfa her seferinde eksik sıkıştırılmış geldi, `curl` denemesi zaman aşımına uğradı | - | - | 4 | Erişilemedi |
| MERAM | Konya, Aksaray, Karaman, Kırşehir, Nevşehir, Niğde | kısıt yok | Planlı kesintiler `cc.meramedas.com.tr` iframe'inde | - | iframe host'unun robots.txt'i zaman aşımına uğradı | 5 | Erişilemedi |
| YEDAŞ | Samsun, Ordu, Çorum, Amasya, Sinop | yok | Next.js harita, `/api/planli-kesinti-harita` | JSON (tahmin) | İstek zaman aşımına uğradı | 4 | Erişilemedi |
| Aras EDAŞ | Erzurum, Ağrı, Kars, Iğdır, Ardahan, Erzincan, Bayburt | `Disallow: /api/`, `/_next/` | Sayfa kabuğu izinli, veri yasak yollardan geliyor | - | robots.txt | 5 | **Engelli** |
| AYEDAŞ | İstanbul Anadolu yakası | izinli | Enerjisa online sorgu formu | - | reCAPTCHA | 5 | **Engelli** |
| Toroslar EDAŞ | Adana, Gaziantep, Hatay, Kilis, Mersin, Osmaniye | izinli | AYEDAŞ ile aynı form | - | reCAPTCHA | 4 | **Engelli** |
| Başkent EDAŞ | Ankara, Zonguldak, Karabük, Bartın, Çankırı, Kastamonu, Kırıkkale | izinli | AYEDAŞ ile aynı form | - | reCAPTCHA | 4 | **Engelli** |
| OEDAŞ | Eskişehir, Afyonkarahisar, Bilecik, Kütahya, Uşak | izinli | ASP.NET form (il/ilçe) | - | reCAPTCHA | 5 | **Engelli** |
| SEDAŞ | Sakarya, Kocaeli, Bolu, Düzce | kısıt yok | `/Tr/SupplyContinuityUrl/PlannedView` form | - | reCAPTCHA | 4 | **Engelli** |

### Neden bu üçü

- **AEDAŞ ve ÇEDAŞ:** BEDAŞ için yazılacak collector'ın host ve kaynak kodu (`BEDAS`, `AEDAS`, `CEDAS`) parametreli hali. Tarama başına planlı için 1, arıza için 1 istek, artı ilk kez görülen trafolar için birkaç konum isteği (cache'lenecek). Üç bölge neredeyse bedava geliyor.
- **KCETAŞ:** Bugün ve sonraki birkaç gün için tarih başına tek POST yeterli. Cevap GeoJSON: ilçe, adres (mahalle), tür (`Bildirimli`), başlangıç, bitiş ve poligon. Poligon, ileride mahalle seviyesine inmek için hazır veri. Farklı bir parser olması "yeni kaynak = yeni sınıf + test" fikrini de gösteriyor.
- Çoruh ve Fırat da iyi (tek parser, 9 il), ama sadece planlı veriyor ve il başına ayrı istek gerekiyor. v1.2 için sıradaki adaylar.

Örnek kayıtlar:

```
AEDAŞ GetItemsData: {"id": "10624928", "plannedOutage": {"reason": "Bakım Çalışması", "city": "ANTALYA", "county": "ELMALI",
  "startDateTime": "2026-09-10 09:00:00", "endDateTime": "2026-09-10 16:00:00", "message": "...", "lat": "...", "lon": "..."}}
KCETAŞ kesinti-sorgu.php: {"type": "Feature", "properties": {"ilce": "PINARBAŞI", "adres": " SOLAKLAR MAH. PINARBAŞI KAYSERİ",
  "tur": "Bildirimli", "baslangic": "2026-09-11T09:00:00", "bitis": "2026-09-11T17:00:00"}, "geometry": {"type": "Polygon", ...}}
Çoruh GetKesintiler: ARTVİN | YUSUFELI | 17.09.2026 | 09:30 | 16:00 | İşletme Bakım Çalışması (Ekip) | BADEMKAYA KÖYÜ KIRAVET MEVKİİ | 11.09.2026
UEDAŞ sec.asp: <kesinti><metinTablo>&lt;tr&gt;&lt;td&gt;15 Eylül Salı&lt;/td&gt;&lt;td&gt;09:00-13:00&lt;/td&gt;&lt;td&gt;SDK DEPLASE&lt;/td&gt;...
```

## Büyükşehir su idareleri (10)

| İdare | robots.txt | Kesinti verisi | Format | Zorluk | İstek | Durum |
|---|---|---|---|---|---|---|
| İZSU (İzmir) | `Disallow: /api/`, `/icons/` | `/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama`, sunucuda render edilen tablo | HTML tablo: ilçe, mahalleler, iş adı, başlangıç, bitiş, açıklama | Az. API yasak ama veri sayfanın içinde | 5 | **Öneri (v1)** |
| ASKİ (Ankara) | kısıt yok | `/TR/Kesinti.aspx`, sunucuda render edilen liste | HTML: ilçe, arıza/planlı, arıza tarihi, tamir tarihi, detay metni | Mahalleler detay metninin içinde | 4 | **Öneri (v1.1)** |
| BUSKİ (Bursa) | yok | `/gunluk-su-kesintileri`, sayfaya gömülü GeoJSON | GeoJSON (EPSG:2320): kesinti no, ilçe, mahalle, açıklama, planlanan başlangıç/bitiş, durum | Koordinatlar WGS84'e çevrilmeli | 3 | Uygun, yedek |
| MESKİ (Mersin) | yok | `online.meski.gov.tr/meta/subscription/interruptions`, sayfaya gömülü JSON | JSON: ilçe ve mahalle listesi, başlangıç, bitiş, sebep, aktif | Az | 4 | Uygun, yedek |
| ASAT (Antalya) | yok | `kesinti.asat.gov.tr/dbo_kesintiListe/list` (PHPRunner) | HTML liste | Tarama anında kayıt yoktu, alanlar görülemedi | 5 | Belirsiz |
| KASKİ (Kayseri) | kısıt yok | `/su-kesintileri` tablosu | HTML tablo: tarih, ilçe, mahalle, tip, saatler, açıklama | Tarama anında tablo boştu | 3 | Muhtemelen uygun |
| KOSKİ (Konya) | kısıt yok | `/koski/ariza-ve-kesintiler` | - | Sayfada veri bulunamadı | 3 | Belirsiz |
| ESKİ (Erzurum) | izinli | Kesintiler tek tek haber sayfası olarak | HTML metin | Yapısal liste yok. Not: `eski.gov.tr` Erzurum'un idaresi çıktı, Eskişehir'e bakılmadı | 3 | Zor |
| İSU (Kocaeli) | kısıt yok | Kesinti sayfası bulunamadı (sadece arıza ihbar) | - | - | 2 | Veri yok |
| GASKİ (Gaziantep) | kısıt yok | Kesinti sayfası bulunamadı | - | - | 4 | Veri yok |

Örnek kayıtlar:

```
İZSU: BAYRAKLI | ALPASLAN, BAYRAKLI, ÇİÇEK, FUAT EDİP BAKSI | Deplase çalışması | 10.09.2026 - 22:00 | 11.09.2026 - 06:00 | ...
ASKİ: MAMAK | Arıza Kaynaklı | Arıza Tarihi: 11.09.2026 13:00:00 | Tamir Tarihi: 11.09.2026 23:00:00 | Detay: PLANSIZ SU KESİNTİSİ: ...
BUSKİ: {"SU_KESINTI_NO": 2186, "ILCE_ADI": "NİLÜFER", "MAHALLE_ADI": "IŞIKTEPE", "PLANLANAN_BASLANGIC_TARIHI": "11/09/2026 12:15",
  "PLANLANAN_BITIS_TARIHI": "11/09/2026 13:15", "DURUM": "Kesildi"}
MESKİ: {"districtList": [{"district": "BOZYAZI", "quarterList": [{"quarter": "AKCAMİ"}, {"quarter": "NARİNCE"}]}],
  "startDate": "2026-09-11T10:11:00.000", "finishDate": "2026-09-11T15:00:00.000", "isActive": 1, "cause": "..."}
```

### Su önerileri

- **v1 canlı su: İZSU.** Tek istekte ilçe, mahalle listesi, başlangıç ve bitiş ayrı kolonlarda geliyor. Normalizasyon en kolay olanı bu. robots.txt `/api/`'yi yasaklıyor, biz de API'ye değil, izinli olan sayfaya gidiyoruz.
- **v1.1 (doğalgaz yerine): ASKİ.** Ankara büyük bir şehir ve veri canlı, arıza kaynaklı kesintiler anında listeleniyor. Mahalleleri detay metninden çıkarmak gerekecek, BEDAŞ'taki mesaj ayrıştırmasına benzer bir iş.
- **Yedekler:** BUSKİ (kesinti numarası var, `external_id` olarak kullanılabilir, poligon da var) ve MESKİ (yapısal JSON).

## Çıkarılanlar ve kara liste

Kararın gereği bu kaynaklara bir daha istek atılmayacak. Collector'ın konfigürasyonunda da bu host'lar yer almayacak.

| Kaynak / host | Neden |
|---|---|
| `www.igdas.istanbul`, `www.igdas.com.tr` (İGDAŞ) | robots.txt `Disallow: /` |
| `harita.iski.gov.tr` | WAF "Request Rejected" |
| `iskiapi.iski.istanbul` | Gömülü token istiyor, kullanılmıyor |
| `data.ibb.gov.tr/api/` (sadece bu yol) | robots.txt `Disallow: /api/`. Veri seti sayfaları ve indirme linkleri izinli |
| `arasedas.com` `/api/`, `/_next/` | robots.txt |
| `online.ayedas.com.tr`, `online.toroslaredas.com.tr`, `online.baskentedas.com.tr` | reCAPTCHA |
| `www.osmangaziedas.com.tr` planlı kesinti formu | reCAPTCHA |
| `www.sedas.com` PlannedView | reCAPTCHA |
| `api.dedas.com.tr`, `www.vedas.com.tr`, `cc.meramedas.com.tr`, `www.yedas.com/api/` | Zaman aşımı. Engel olup olmadığı belli değil, temkinli olup tekrar denenmeyecek |

## İstek sayıları

Keşif boyunca kaynak başına gönderilen istekler. robots.txt istekleri dahil. DNS arızası yüzünden sunucuya hiç çıkmayan denemeler hariç. Ara sertifika indirmeleri (CA sunucuları) ve DoH sorguları (1.1.1.1) kaynak site olmadığı için sayılmadı.

| Kaynak | İstek | | Kaynak | İstek |
|---|---|---|---|---|
| BEDAŞ | 9 | | İSKİ (iski.istanbul, iskiapi, harita) | ~10 |
| AYEDAŞ | 5 | | İBB Açık Veri | 10 |
| AEDAŞ | 8 | | İGDAŞ (sadece robots.txt) | 2 |
| ÇEDAŞ | 6 | | Başkentgaz | 7 |
| CK Enerji API robots.txt (ortak) | 1 | | İzmirgaz | 5 |
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

Toplam yaklaşık 168 istek, 35 kaynağa yayılmış. En çok istek ASKİ ya da AEDAŞ gibi umut veren kaynaklara değil, ilk turdaki İSKİ ve İBB'ye gitti. Hiçbir kaynak 10 isteği geçmedi.

## Fixture'lar

Repoda sadece v1'e önerilen elektrik kaynaklarının fixture'ları duruyor (`services/collector/src/test/resources/fixtures/`):

- `bedas/` (Faz 1'den)
- `aedas/planned-getitemsdata.json`, `aedas/unplanned-retrieve-outages.json`
- `cedas/planned-getitemsdata.json`, `cedas/unplanned-retrieve-outages.json` (o an boş liste)
- `kcetas/planli-kesintiler-bakimlar.html`, `kcetas/kesinti-sorgu-2026-09-11.json`

Bir istisna yaptım: `iski/ibb-su-kesintileri-2023-2024.xlsx` da repoda kaldı. Günlük İBB taraması onaylandı ve Faz 2'deki parser testinin bu dosyaya ihtiyacı var. İstemezsen çıkarırım, Faz 2'de bir istekle tekrar indirilir.

Diğer kaynakların yanıtları repoda yok, ne gördüğüm bu dokümanda duruyor. İZSU ve ASKİ onaylanırsa fixture'ları Faz 2'de parser testiyle birlikte alınacak.
