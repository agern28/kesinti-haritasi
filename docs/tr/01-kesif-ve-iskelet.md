# Faz 1 - Keşif ve iskelet

Tarih: 2026-09-11

Bu fazda iki iş vardı: dört kaynağın (BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ) verisini nasıl sunduğunu çıkarmak ve boş ama ayağa kalkan bir monorepo kurmak. İlk keşiften sonra kararlar alındı ve su ile doğalgaz için ikinci bir tur keşif yaptım. İkisi de aşağıda. Üçüncü turda 21 elektrik dağıtım şirketini ve 10 büyükşehir su idaresini taradım: [01-kaynak-taramasi.md](01-kaynak-taramasi.md).

## Özet ve kararlar

| Kaynak | Tür | Karar | Neden |
|---|---|---|---|
| BEDAŞ | Elektrik (Avrupa yakası) | v1'de | Açık JSON endpoint'leri, planlı ve arıza ayrı |
| İSKİ | Su | v1'de, İBB Açık Veri'den | İSKİ'nin kendi API'si gömülü token istiyor. İBB'deki "Su Kesintileri" dosyası robots.txt'e göre indirilebiliyor, ama geçmiş veri |
| AYEDAŞ | Elektrik (Anadolu yakası) | v1'de yok | Veri sadece reCAPTCHA'lı adres formunun arkasında |
| İGDAŞ | Doğalgaz | yok | robots.txt `Disallow: /`, İBB'de İGDAŞ kesinti veri seti yok |
| Başkentgaz | Doğalgaz (Ankara) | yok | Sitesinde kesinti yayını yok |
| İzmirgaz | Doğalgaz (İzmir) | yok | Sadece tek sokak için sorgu, liste yok |

Alınan kararlar (2026-09-11):
- v1 kaynakları: BEDAŞ ve İSKİ (İBB Açık Veri). AYEDAŞ v1'de yok.
- İSKİ'nin sitesine gömülü token'ı kullanmıyoruz.
- Veri modeline `external_id` ve `lat`/`lon` eklendi (hepsi nullable). Tekilleştirmede `external_id` varsa `(source, external_id)`, yoksa hash. Ayrıntı: [veri-modeli.md](veri-modeli.md).
- Doğalgaz için uygun kaynak bulunamadı, Faz 9'daki madde beklemeye alındı (aşağıda).

## Nasıl istek attım

İlk turda istekler curl ile, `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)` User-Agent'ıyla atıldı. Önce robots.txt'e baktım, sonra ana sayfa, kesinti sayfası ve sayfanın kullandığı JS dosyalarına.

İlk turda bir hata yaptım: İBB Açık Veri'de robots.txt'i aynı script'te çektim ama sonucuna göre durmadım, `Disallow: /api/` olan yola 2 istek gitti. İkinci turda bunun için küçük bir yardımcı yazdım (`polite.py`, repoda değil, keşif için): her istekten önce host'un robots.txt'ini okuyor, `*` ve `$` desteğiyle en uzun eşleşen kuralı uyguluyor, yasaksa isteği hiç atmıyor, `Crawl-delay` varsa istekler arasında o kadar bekliyor. İkinci turdaki bütün istekler bu yardımcıdan geçti. Collector'daki robots kontrolü Faz 2'de aynı mantıkla Java'da yazılacak.

Repoda sadece v1'e önerilen elektrik kaynaklarının (BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ) ve Faz 2'de toplanacak İBB su kesintisi dosyasının fixture'ları tutuluyor (`services/collector/src/test/resources/fixtures/`). Diğer kaynaklardan aldığım yanıtlar repodan çıkarıldı; ne gördüğüm bu dokümanda ve tarama raporunda not olarak duruyor.

## BEDAŞ

İki ayrı kaynak var, ikisi de JSON ve kimlik doğrulaması yok. robots.txt yok (404).

**Planlı kesintiler: `GET https://www.bedas.com.tr/GetItemsData`**

`www.bedas.com.tr/elektrik-kesintisi-sorgulama` sayfası il/ilçe seçtirip `POST /elektrik-getir` ile ilçe bazında sorguluyor. Ama sayfa açılırken `GET /GetItemsData` ile bütün ilçelerin listesini tek seferde çekiyor. Yani ilçe ilçe 25 istek atmak yerine tek istek yetiyor.

- Boyut: yaklaşık 170 KB, denediğimde 218 kayıt, 25 ilçe.
- Tarih aralığı: dünden 3 gün sonrasına kadar (10.09 - 14.09).
- Kayıt yapısı:

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

- Mahalle listesi ayrı alan olarak yok, `message` metninin içinde: `<İL> <İLÇE> ilce <MAHALLE> mah <SOKAK>, <SOKAK> sk / <MAHALLE2> mah ... sk  bölgelerinde`. Mahalleleri bu metinden çıkarmak gerekecek.
- Saatler saat dilimi bilgisi olmadan geliyor (`2026-09-10 09:00:00`), Europe/Istanbul kabul edilecek.
- `lat`/`lon` başında boşluk olan string. Artık `outage.lat`/`lon` kolonlarına gidecek.
- 218 kayıttan birinde `city2`/`county2` dolu (iki ilçeye yayılan kesinti). Nadir ama parser bunu kaldırmalı.
- `id` -> `external_id`. `updateDateTime` değişiklik takibinde işe yarar.
- Fixture: `bedas/planned-getitemsdata.json`, ilçe bazlı sorgunun örneği `bedas/planned-elektrik-getir-arnavutkoy.json`.

**Anlık kesintiler: `GET https://kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages`**

`kesinti.bedas.com.tr` bir Vite SPA. Bundle'ın içinde `kesintiapi.ckenerji.com.tr/BEDAS/...` endpoint'leri duruyor.

- Denediğimde 73 satır, 19 farklı `OUTAGE_NO`. Her satır bir trafo, yani aynı kesinti birden fazla satırda tekrar ediyor. `OUTAGE_NO` -> `external_id`, satırlar bununla gruplanacak.
- Alanlar: `OUTAGE_NO`, `BILDIRIM_TURU` (`Bildirimli` / `Bildirimsiz`), `RPTD_DATE` (ISO, +03:00), `EST_REPAIR_TIME`, `SURE`, `SCADA_INITIATED`, `XFMR_ID`, `CBS_TM_NO`, `MESSAGE`.
- `Bildirimsiz` arıza demek (7 satır, mesajı "Şebeke arızası, ekip çalışıyor..."). `Bildirimli` şu an devam eden planlı kesinti.
- En büyük sorun: satırlarda ilçe/mahalle adı yok, sadece trafo numarası (`CBS_TM_NO`) var. Konum için `GET /BEDAS/GetLocation?tmno=<no>` çağrılıyor ve `{"results":[{"ilce":"GAZİOSMANPAŞA","mahalle":"BARBAROS HAYRETTİN PAŞA"}]}` dönüyor. BEDAŞ'ın kendi sitesi her 5 dakikada bütün trafolar için bu çağrıyı 30'arlı paketlerle yapıyor. Biz bunu yapmayacağız: trafo yer değiştirmediği için `trafo -> ilçe/mahalle` eşlemesini Redis'te uzun süreli cache'leyeceğiz, sadece ilk kez görülen trafo için istek atacağız. İlk taramada birkaç düzine istek, sonrasında tarama başına birkaç istek bekliyorum. Bu istekler de sırayla ve aralarında gecikmeyle gidecek.
- `RetrieveOutageTransformersList` trafo poligonlarını veriyor. v1'de mahalle poligonu yok, kullanmayacağız.
- Fixture: `bedas/unplanned-retrieve-outages.json`, `bedas/getlocation-28175.json`.

**Planlı/arıza ayrımı ve çakışma:** Şu an devam eden planlı bir kesinti hem `GetItemsData`'da hem `RetrieveOutages`'da (`Bildirimli`) görünüyor, ama iki taraftaki id'ler farklı (`36878730` ile `4851785` gibi), kolayca eşleşmiyor. Faz 2'de planlıları sadece `GetItemsData`'dan, arızaları sadece `RetrieveOutages` içindeki `Bildirimsiz` satırlardan alacağım. Böylece aynı kesinti iki kere sayılmaz.

**Zamanlama:** `RetrieveOutages` 5 dakikada, `GetItemsData` 15 dakikada bir.

## AYEDAŞ (v1'de yok)

- robots.txt (`www` ve `online` alt alan adları): her şeye izin.
- Kesinti bilgisinin tek yeri `https://online.ayedas.com.tr/elektrik-kesintisi-sorgulama`. Listeleme yapan bir sayfa yok, site haritasında da başka bir kesinti sayfası çıkmadı.
- Sayfa bir adres formu: İl, İlçe, Bucak, Belde, Mahalle, Sokak. Gönderince `POST /elektrik-kesintisi-sorgulama` çağrılıyor. Yanıtta `planlananKesintiListe` ve `mevcutKesintiListe` var, kayıtlarda `ilAdi`, `ilceAdi`, `mahalleAdi`, `sokakAdi`, `kesintiTipi`, `polygon` alanları bulunuyor. Aslında yapısı çok iyi.
- Engel: formda Google reCAPTCHA var (`FormValidation` içinde `CaptchaValueCheck`, sunucu captcha hatasında `state: 3` dönüyor), üstüne `__RequestVerificationToken`. İlçe bazında bile sorgu atmak için captcha çözmek gerekiyor.
- robots.txt izin verse de veri captcha'nın arkasında. Captcha, sitenin "otomatik sorgu istemiyorum" demesi. Onu aşmak (captcha çözme servisi vs.) bu projede yapılacak bir şey değil.
- Geriye AYEDAŞ/Enerjisa'ya yazıp veri erişimi istemek ya da açık veri yayınlarsa eklemek kalıyor.
- Kayıtlı yanıtlar repodan çıkarıldı (v1 kaynağı değil). Formun yapısı yukarıda.

## İSKİ

### İSKİ'nin kendi sitesi (kullanılmıyor)

- robots.txt: `iski.istanbul` ve `iskiapi.iski.istanbul` için yok (404).
- Sayfa: `https://iski.istanbul/abone-hizmetleri/ariza-kesinti`. Nuxt SPA, HTML'de veri yok.
- Sayfanın JS'i veriyi `https://iskiapi.iski.istanbul/api/iski/bolgeselAriza/listesi` ve `.../bolgeselAriza/arizaDetayiFiltreli?ilceKodu=&mahalleKodu=` endpoint'lerinden çekiyor. Şablonda kullanılan alanlar: `ilceKodu`, `mahalleAdi`, `arizaNeviAciklamasi`, `baslamaTarihi`, `tahminiBitisTarihi`.
- API, `Authorization` başlığı olmadan `403 Forbidden` dönüyor. Sitenin JS'inde sabit bir Bearer token gömülü. Bu token bize verilmedi, kullanmıyoruz (karar).
- Arıza kayıtlarının link verdiği `harita.iski.gov.tr`'de JS dosyalarına attığım istekler WAF tarafından `Request Rejected` ile reddedildi.
- Kayıtlı yanıtlar (token'sız 403 cevabı, sayfa kabuğu, WAF sayfası) repodan çıkarıldı.

### İBB Açık Veri (v1'de kullanılacak)

`data.ibb.gov.tr/robots.txt`:

```
User-agent: *
Disallow: /dataset/rate/
Disallow: /revision/
Disallow: /dataset/*/history
Disallow: /api/
Crawl-Delay: 10
```

Veri seti sayfaları (`/dataset/<ad>`) ve dosya indirme linkleri (`/dataset/<uuid>/resource/<uuid>/download/<dosya>`) bu kurallarla izinli. CKAN API'si (`/api/`) yasak. O yüzden dosya listesini API'den değil veri seti sayfasının HTML'inden okuyacağız. İstekler arasında 10 saniye bekleyeceğiz.

İSKİ organizasyonunun altında 18 veri seti var. İki tanesi işimize yarayabilir gibi göründü:

- **İSKİ Duyuruları** (`/dataset/iski-duyurulari`): yıllık XLSX dosyaları, 2019-2023. İndirip baktım: `tarih | link | baslik` kolonları, içerik basın açıklamaları ve etkinlik duyuruları. Kesinti verisi değil, kullanmıyoruz.
- **İstanbul'da Meydana Gelen Su Kesintileri** (`/dataset/istanbul-da-meydana-gelen-su-kesintileri`): iki XLSX dosyası, 2022-2023 ve 2023-2024. Asıl kaynak bu.

2023-2024 dosyası:

- 6.410 satır, 39 ilçe. Tarih aralığı: 2023-02-18 08:47 - 2024-02-19 11:13.
- Kolonlar: `ILCE | KESİNTİ SEBEP | ARIZA KESİNTİ TARİHİ | ARIZA BİTİS TARİHİ | CALISMA YERİ | MAHALLE`
- Örnek: `ADALAR | 100 MM ÇAPLI ŞEBEKE HATTI ARIZASI | 12/02/2024 13:30:10 | 12/02/2024 20:30:00 | BURGAZADA GÖNÜLLÜ CAD.ÜZERINDE | BURGAZADA MAH`
- Tarih formatı `dd/MM/yyyy HH:mm:ss`, saat dilimi yok, Europe/Istanbul kabul edilecek. Bitiş tarihi her satırda dolu.
- `MAHALLE` virgülle ayrılmış liste (`MADEN MAH,NİZAM MAH`), "MAH" ekiyle. Normalizasyonda temizlenecek.
- Kaynak id'si yok, `external_id` null kalacak, tekilleştirme hash ile.
- Hepsi arıza kaynaklı kesinti, planlı/arıza ayrımı yok. `planned = false`.

**Önemli sınırlama:** Bu veri canlı değil. En yeni kayıt 2024-02-19 tarihli, veri seti 2024-03'ten beri güncellenmemiş. Haritada bugün aktif bir su kesintisi göstermeyecek. v1'de "su" katmanı olacak ama sadece geçmiş kesintiler (ilçe bazında son dönemde kaç kesinti oldu gibi) olarak işe yarar. Canlı su verisi için İSKİ'ye yazıp erişim istemek hâlâ tek yol.

**Zamanlama önerisi:** Dosyalar yılda bir ekleniyor. 5 ya da 15 dakikada bir indirmek anlamsız ve İBB'ye yük. Önerim: veri seti sayfasını günde bir kez okumak, yeni bir dosya linki ya da değişmiş "Son Güncelleme" görürsem sadece o dosyayı indirmek. Bu, CLAUDE.md'deki 5/15 dakika kuralından sapma olduğu için onayını istiyorum (kural arıza ve planlı kesinti sayfaları için yazılmış, burası bir veri seti).

- Fixture: `iski/ibb-su-kesintileri-2023-2024.xlsx` (Faz 2'deki günlük İBB collector'ının parser testi için repoda). Duyurular dosyası kesinti verisi olmadığı için çıkarıldı.

## Doğalgaz

### İGDAŞ

- `https://www.igdas.istanbul/robots.txt` ve `https://www.igdas.com.tr/robots.txt`: ikisi de `User-agent: *` / `Disallow: /`. Kesinti sayfasına hiç istek atmadım.
- İBB Açık Veri'de İGDAŞ organizasyonunun altındaki veri setleri: bina bilgileri, gaz birim fiyatı ve miktarı, gaz tüketimi, ilçe bazında aylık tüketim, ilçelere göre abone sayıları, kullanım sınıfı bazında tüketim, yatırım türü ve uzunluk bilgileri. Hiçbiri kesinti verisi değil. Linkler robots.txt'e göre izinli, ama indirecek bir kesinti dosyası yok.
- robots.txt kayıtları repodan çıkarıldı, içerikleri yukarıda.

### Başkentgaz (Ankara)

- Alan adı `www.baskentdogalgaz.com.tr` (`baskentgaz.com.tr` DNS'te yok).
- robots.txt: `User-agent: *` / `Disallow:` (boş), her şeye izin. API alt alan adı `bskapiv1.baskentdogalgaz.com.tr`'de robots.txt yok (404).
- TLS: sunucu ara sertifikayı göndermiyor (GoDaddy G2), curl ve Python doğrulamada düşüyor. `-k` ile doğrulamayı kapatmak yerine sertifikadaki AIA adresinden ara sertifikayı alıp ayrı bir CA paketiyle bağlandım. Collector'da kullanılsaydı Java truststore'una aynı ara sertifika eklenmesi gerekirdi.
- Site bir React SPA, içeriği `https://bskapiv1.baskentdogalgaz.com.tr/api/` altındaki bir CMS API'sinden alıyor. Bundle'da kesinti ile ilgili bir endpoint yok. Bütün menü ağacını (`menus/ByDomainMenus/1`, 460 öğe) tarayınca "kesinti" kelimesi sadece "kesintisiz doğal gaz" gibi tanıtım metinlerinde geçiyor. Duyurular fiyat tarifesi ve ihale duyuruları.
- Sonuç: Başkentgaz planlı ya da arıza kaynaklı kesintileri sitesinde yayınlamıyor, okunacak veri yok.
- Kayıtlı yanıtlar repodan çıkarıldı.

### İzmirgaz

- robots.txt yok (404), kısıt yok.
- TLS: Başkentgaz'la aynı sorun (Sectigo DV R36 ara sertifikası gönderilmiyor), aynı yöntemle bağlandım.
- Kesinti bilgisi "Sokağımda Gaz Var mı?" sayfasında (`/SokagimdaGazVarmi.php`). Sayfa içeriği `pages/islemler/SokagimdaGazVarmi.php` parçasından yükleniyor. Parça bir form: ilçe seç, mahalle seç, sokak seç. Sokak seçilince `POST gaz.php` ile sadece sokak kodu gönderiliyor ve o sokak için cevap geliyor. Captcha yok.
- Sorun: liste yok. Bütün kesintileri görmek için İzmir'deki her sokağı tek tek sorgulamak gerekiyor. İzmir'de on binlerce sokak var, 15 dakikalık bir tarama bunu kaldıramaz, siteye de ciddi yük olur. "Kaynak sitelere nazik ol" kuralıyla bağdaşmıyor.
- Kayıtlı yanıtlar repodan çıkarıldı.

### Doğalgaz sonucu

Karar "İBB'deki İGDAŞ verisi izinliyse onu, değilse Başkentgaz ya da İzmirgaz'dan verisi daha düzgün olanı" idi. İBB'de İGDAŞ kesinti verisi yok. Başkentgaz hiç kesinti yayınlamıyor. İzmirgaz sadece sokak bazında sorgu veriyor. Üçünden de nazik bir collector çıkmıyor, bu yüzden birini seçmedim. Faz 9'daki doğalgaz maddesini "kaynak bulunana kadar beklemede" olarak güncelledim. Seçenekler aşağıda.

## Kaç istek attım

- İlk tur: BEDAŞ 9, AYEDAŞ 5, İSKİ 10 civarı (WAF'a takılınca durdum), İGDAŞ 2 (robots.txt), İBB 3 (robots.txt ve robots'a aykırı 2 API isteği).
- İkinci tur (hepsi robots kontrollü): İBB 7 (robots.txt, 4 sayfa, 2 XLSX), Başkentgaz 7 (2 robots.txt, ana sayfa, JS bundle, 3 API çağrısı), İzmirgaz 5 (robots.txt, ana sayfa, sorgu sayfası, form parçası, yanlış yoldan istediğim için 404 dönen bir JS). Ayrıca iki ara sertifika CA'ların kendi sunucularından (GoDaddy, Sectigo) indirildi.

## İskelet

### Ne kurdum

- `services/collector` ve `services/api`: Spring Boot 4.1.1 (şu anki son kararlı sürüm), Java 21, Maven. Şimdilik sadece `spring-boot-starter-webmvc`, actuator ve Prometheus registry var. Liveness/readiness probe'ları açık (`/actuator/health/liveness`, `/actuator/health/readiness`), `/actuator/prometheus` açık. Her serviste bu üç endpoint'i gerçek HTTP ile kontrol eden bir test var.
- `frontend`: React 19 + Vite 8. Şimdilik sadece başlık ve köşede sürüm/ortam etiketi var (`VITE_APP_VERSION`, `VITE_APP_ENV` build argümanından geliyor). nginx `/api/` isteklerini api servisine proxy'liyor. `/api/stream` için buffering kapalı, SSE Faz 3'te buna ihtiyaç duyacak.
- `docker-compose.yml`: postgres 18, redis 8, collector, api, frontend. Her birinde healthcheck ve `mem_limit` var. Servisler bağımlılıkları healthy olmadan başlamıyor. Veritabanı kullanıcı adı ve parolası `.env` dosyasından geliyor. Repoda sadece `.env.example` var, `.env` yoksa compose açık bir hata mesajıyla duruyor.
- Boş klasörler (`helm/*`, `gitops/*`, `infra/*`, `loadtest`, `.github/workflows`) `.gitkeep` ile duruyor.
- `.gitignore` içinde `.env`, `*.tfvars`, `*.tfstate`, `kubeconfig*`, `*secret*.yaml` baştan dışarıda. Sadece `*.example` olanlar girebiliyor.

### Neden böyle

- Dockerfile'lar şimdilik basit iki aşamalı (Maven ile build, JRE alpine ile çalıştır, non-root kullanıcı). Asıl sıkılaştırma Faz 5'te. Compose'un `--build` ile çalışması için şimdiden lazımdı.
- JVM'lere `-XX:MaxRAMPercentage=75` verdim, container limitine göre heap alıyorlar. Sunucu 4 GB olacağı için bunu baştan alışkanlık yapmak istedim.
- Frontend image'ı `nginx-unprivileged`, root olmadan 8080'de dinliyor. Hostta 3000'e map'lendi.
- Postgres 18 image'ı veri dizinini `/var/lib/postgresql` altına taşıdı, volume'ü oraya bağladım (eski `/var/lib/postgresql/data` yolu 18'de uyarı veriyor).

### Sonuç

- `mvn verify`: collector 3/3, api 3/3 test yeşil.
- `npm run build` (node:24-alpine container'ında): yeşil.
- `docker compose up --build`: beş container da healthy. collector ve api `/actuator/health` için `{"status":"UP"}` dönüyor, frontend 3000'de sayfayı veriyor, `localhost:3000/api/...` isteği nginx üzerinden api'ye gidiyor (henüz endpoint olmadığı için 404, beklenen bu).
- Boştayken bellek: collector 129 MiB / 384, api 131 MiB / 512, postgres 41 MiB, redis 7 MiB, frontend 13 MiB. Toplam 320 MiB civarı, 4 GB'lık sunucu için rahat bir başlangıç.

### Nerede takıldım

- Frontend container'ı ilk seferde `unhealthy` çıktı, halbuki `/healthz` hosttan cevap veriyordu. Health log'da `wget: can't connect to remote host: Connection refused` vardı. Alpine'da `localhost` önce `::1`'e çözülüyor. nginx-unprivileged image'ı normalde `default.conf`'a IPv6 listen ekliyor, ama ben o dosyayı kendi config'imle ezdiğim için nginx sadece IPv4'te dinliyordu. Healthcheck'i `127.0.0.1` yapınca düzeldi.
- Windows tarafından `wsl.exe -- bash -c '...'` ile komut çalıştırınca `$DEGISKEN`'ler boş genişliyordu, çünkü wsl.exe komut satırını bir kabuktan daha geçiriyor. İlk robots.txt denemesi `https://robots.txt/` adresine gitti. Çözüm: komutları script dosyasına yazıp `wsl.exe -- bash script.sh` ile çalıştırmak.
- WSL'de Node kurulu değil. Frontend'in `package-lock.json`'ını ve build'ini `node:24-alpine` container'ında yaptım. Bilgisayara ayrıca Node kurmak gerekmiyor.
- İSKİ sunucusu yavaş cevap veriyor, 100 KB'lık bir JS dosyası birkaç dakika sürdü.
- İlk commit'lerde `docker-compose.yml` ve README'de lokal bir Postgres parolası duruyordu. Sadece lokal de olsa CLAUDE.md "parola repoya girmez" diyor. Push'tan önce parolayı `.env`'e taşıdım ve henüz push edilmemiş commit'leri yeniden yazdım (compose, fixture ve README commit'leri). Aynı sırada AYEDAŞ fixture'ındaki reCAPTCHA site key'ini de maskeledim. Site key zaten herkese açık bir değer, ama "anahtar gibi görünen bir şey repoda yok" demek daha temiz.

## Açık kararlar (senden)

1. İSKİ verisi (İBB) geçmiş veri, en yenisi 2024-02-19. v1'de su katmanını bu haliyle, "geçmiş kesintiler" olarak mı gösterelim? Canlı su verisi için İSKİ'ye erişim talebi yazmamı ister misin?
2. İSKİ (İBB) taramasını günde bir yapmayı onaylıyor musun? CLAUDE.md'deki 5/15 dakika kuralından bir sapma bu.
3. Doğalgaz için uygun kaynak yok. Seçenekler: (a) İGDAŞ ya da Başkentgaz'dan izin/veri talep etmek, (b) v1.1'den doğalgazı çıkarıp yerine başka bir şey koymak (ör. plan'daki v1.2 şehirlerinden birinin elektrik ya da su kaynağı), (c) İzmirgaz'ı sadece seçili birkaç sokak için sorgulamak. Benim önerim (a) ile (b)'nin birlikte yürümesi.
