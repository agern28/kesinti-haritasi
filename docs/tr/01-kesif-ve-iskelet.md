# Faz 1 - Keşif ve iskelet

Tarih: 2026-09-11

Bu fazda iki iş vardı: dört kaynağın (BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ) verisini nasıl sunduğunu çıkarmak ve boş ama ayağa kalkan bir monorepo kurmak.

## Kaynak keşfi

Bütün istekler curl ile, `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)` User-Agent'ıyla atıldı. Önce robots.txt'e baktım, sonra kaynak başına ana sayfa, kesinti sayfası ve sayfanın kullandığı JS dosyaları. Kayıtlı örnekler `services/collector/src/test/resources/fixtures/<kaynak>/` altında.

Kısa sonuç: kurallarımıza (robots.txt'e uy, captcha ve erişim kontrolü aşma) harfiyen uyarsak v1'de temiz okunabilen tek kaynak BEDAŞ. Diğer üçünde birer engel var, aşağıda ayrıntısı ve önerim var.

| Kaynak | Tür | robots.txt | Veri nasıl geliyor | Durum |
|---|---|---|---|---|
| BEDAŞ | Elektrik (Avrupa yakası) | yok (404) | Açık JSON endpoint'leri | v1'e uygun |
| AYEDAŞ | Elektrik (Anadolu yakası) | her şeye izin | Adres formu + reCAPTCHA | v1'den çıkarılmalı |
| İSKİ | Su | yok (404) | JSON API, gömülü Bearer token istiyor; harita sitesinde WAF | v1'den çıkarılmalı (ya da senin kararın) |
| İGDAŞ | Doğalgaz | `Disallow: /` | Bakmadım | robots.txt yüzünden taranamaz |

### BEDAŞ

İki ayrı kaynak var, ikisi de JSON ve kimlik doğrulaması yok.

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
- `lat`/`lon` başında boşluk olan string.
- 218 kayıttan birinde `city2`/`county2` dolu (iki ilçeye yayılan kesinti). Nadir ama parser bunu kaldırmalı.
- `id` ve `updateDateTime` var, değişiklik takibinde işe yarar.
- Fixture: `bedas/planned-getitemsdata.json`, ilçe bazlı sorgunun örneği `bedas/planned-elektrik-getir-arnavutkoy.json`.

**Anlık kesintiler: `GET https://kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages`**

`kesinti.bedas.com.tr` bir Vite SPA. Bundle'ın içinde `kesintiapi.ckenerji.com.tr/BEDAS/...` endpoint'leri duruyor.

- Denediğimde 73 satır, 19 farklı `OUTAGE_NO`. Her satır bir trafo, yani aynı kesinti birden fazla satırda tekrar ediyor.
- Alanlar: `OUTAGE_NO`, `BILDIRIM_TURU` (`Bildirimli` / `Bildirimsiz`), `RPTD_DATE` (ISO, +03:00), `EST_REPAIR_TIME`, `SURE`, `SCADA_INITIATED`, `XFMR_ID`, `CBS_TM_NO`, `MESSAGE`.
- `Bildirimsiz` arıza demek (7 satır, mesajı "Şebeke arızası, ekip çalışıyor..."). `Bildirimli` şu an devam eden planlı kesinti.
- En büyük sorun: satırlarda ilçe/mahalle adı yok, sadece trafo numarası (`CBS_TM_NO`) var. Konum için `GET /BEDAS/GetLocation?tmno=<no>` çağrılıyor ve `{"results":[{"ilce":"GAZİOSMANPAŞA","mahalle":"BARBAROS HAYRETTİN PAŞA"}]}` dönüyor. BEDAŞ'ın kendi sitesi her 5 dakikada bütün trafolar için bu çağrıyı 30'arlı paketlerle yapıyor. Biz bunu yapmayacağız: trafo yer değiştirmediği için `trafo -> ilçe/mahalle` eşlemesini Redis'te uzun süreli cache'leyeceğiz, sadece ilk kez görülen trafo için istek atacağız. İlk taramada birkaç düzine istek, sonrasında tarama başına birkaç istek bekliyorum. Bu istekler de sırayla ve aralarında gecikmeyle gidecek.
- `RetrieveOutageTransformersList` trafo poligonlarını veriyor. v1'de mahalle poligonu yok, kullanmayacağız.
- Fixture: `bedas/unplanned-retrieve-outages.json`, `bedas/getlocation-28175.json`.

**Planlı/arıza ayrımı ve çakışma:** Şu an devam eden planlı bir kesinti hem `GetItemsData`'da hem `RetrieveOutages`'da (`Bildirimli`) görünüyor, ama iki taraftaki id'ler farklı (`36878730` ile `4851785` gibi), kolayca eşleşmiyor. Faz 2'deki önerim: planlıları sadece `GetItemsData`'dan, arızaları sadece `RetrieveOutages` içindeki `Bildirimsiz` satırlardan almak. Böylece aynı kesinti iki kere sayılmaz.

**Zamanlama:** `RetrieveOutages` 5 dakikada, `GetItemsData` 15 dakikada bir.

### AYEDAŞ

- robots.txt (`www` ve `online` alt alan adları): her şeye izin.
- Kesinti bilgisinin tek yeri `https://online.ayedas.com.tr/elektrik-kesintisi-sorgulama`. Listeleme yapan bir sayfa yok, site haritasında da başka bir kesinti sayfası çıkmadı.
- Sayfa bir adres formu: İl, İlçe, Bucak, Belde, Mahalle, Sokak. Gönderince `POST /elektrik-kesintisi-sorgulama` çağrılıyor. Yanıtta `planlananKesintiListe` ve `mevcutKesintiListe` var, kayıtlarda `ilAdi`, `ilceAdi`, `mahalleAdi`, `sokakAdi`, `kesintiTipi`, `polygon` alanları bulunuyor. Aslında yapısı çok iyi.
- Engel: formda Google reCAPTCHA var (`FormValidation` içinde `CaptchaValueCheck`, sunucu captcha hatasında `state: 3` dönüyor), üstüne `__RequestVerificationToken`. İlçe bazında bile sorgu atmak için captcha çözmek gerekiyor.
- Captcha, sitenin "otomatik sorgu istemiyorum" demesi. Bunu aşmaya çalışmak (captcha çözme servisi vs.) bu projede yapılacak bir şey değil.
- **Öneri: AYEDAŞ v1'den çıkarılsın.** Yol olarak AYEDAŞ/Enerjisa'ya yazıp veri erişimi istemek ya da açık veri yayınlarsa eklemek kalıyor.
- Fixture: `ayedas/elektrik-kesintisi-sorgulama.html` (formun ve JS'in kaydı; bundan parser yazılmaz), `ayedas/robots-www.ayedas.com.tr.txt`.

### İSKİ

- robots.txt: `iski.istanbul` ve `iskiapi.iski.istanbul` için yok (404).
- Sayfa: `https://iski.istanbul/abone-hizmetleri/ariza-kesinti`. Nuxt SPA, HTML'de veri yok.
- Sayfanın JS'i veriyi `https://iskiapi.iski.istanbul/api/iski/bolgeselAriza/listesi` ve `.../bolgeselAriza/arizaDetayiFiltreli?ilceKodu=&mahalleKodu=` endpoint'lerinden çekiyor. Şablonda kullanılan alanlar: `ilceKodu`, `mahalleAdi`, `arizaNeviAciklamasi`, `baslamaTarihi`, `tahminiBitisTarihi`.
- Engel 1: API, `Authorization` başlığı olmadan `403 Forbidden` dönüyor. Sitenin JS'inde sabit bir Bearer token gömülü, axios interceptor'ı her isteğe onu ekliyor.
- Engel 2: Arıza kayıtlarının link verdiği `harita.iski.gov.tr`'de JS dosyalarına attığım istekler WAF tarafından `Request Rejected` ile reddedildi. Tarayıcı dışı istemcilerin açıkça engellendiğini gösteriyor. Bundan sonra İSKİ'ye istek atmayı bıraktım.
- Planlı/arıza ayrımı: sadece arıza listesi gördüm, ayrı bir planlı kesinti sayfası bulamadım.
- Teknik olarak token'ı bundle'dan alıp kullanmak mümkün. Ama bu bize verilmemiş bir kimlik bilgisini kullanmak demek. Token her an değişebilir, WAF da botlara karşı tavırlarını gösteriyor. Token'ı repoya koymak zaten kurallara aykırı.

- Alternatif olarak İBB Açık Veri Portalı'na baktım. "İSKİ Duyuruları" veri seti yıllık XLSX dosyalarından oluşuyor, en son 2024-03'te güncellenmiş. "Su Kesintileri" ve arıza sayısı setleri de geçmişe dönük istatistik. Canlı harita için işe yaramıyorlar, ama v2.1'deki mahalle karnesi için geçmiş veri kaynağı olabilirler.
- Burada bir hata yaptım: `data.ibb.gov.tr/robots.txt` `Disallow: /api/` ve `Crawl-Delay: 10` diyor. robots.txt'i aynı script'te CKAN API'den önce çektim ama sonucuna göre durmadım, `/api/3/action/...` yoluna 2 istek gitti. Oraya başka istek atmadım. Collector'da robots.txt kontrolü kodla yapılacak (Faz 2), izin yoksa istek hiç çıkmayacak. Keşif script'lerinde de artık önce robots.txt'e bakıp sonra ilerliyorum.
- **Öneri: İSKİ v1'den çıkarılsın**, İSKİ'ye yazılıp canlı arıza verisi için erişim istensin. Sen token'la devam etmeyi seçersen token Kubernetes Secret'ta durur, repoya girmez, token değişince collector'ın kırılacağını da kabul etmiş oluruz. Bu karar senin.

- Fixture: `iski/bolgeselariza-listesi-403.json` (token'sız yanıt), `iski/ariza-kesinti-page-shell.html`, `iski/harita-waf-rejected.html`.

### İGDAŞ

- `https://www.igdas.istanbul/robots.txt` ve `https://www.igdas.com.tr/robots.txt`: ikisi de `User-agent: *` / `Disallow: /`.
- Kurallarımızda robots.txt kontrolü var, bu yüzden kesinti sayfasına hiç istek atmadım. Web aramasında İGDAŞ'ın adres bazlı bir sorgulama ekranı olduğu görünüyor, ama taramak robots.txt'e aykırı olur.
- İBB Açık Veri Portalı'nda İGDAŞ'ın tüketim ve abone sayısı veri setleri var, kesinti veri seti yok.
- **Öneri: İGDAŞ taranamaz.** Faz 9'daki "İGDAŞ collector'ı" maddesi bu haliyle yapılamıyor. Seçenekler: İGDAŞ'tan izin/erişim istemek ya da doğalgaz için robots.txt'i izin veren başka bir dağıtım şirketiyle (plan'daki v1.2 listesinde Başkentgaz, İzmirgaz var) başlamak.
- Fixture: `igdas/robots-www.igdas.istanbul.txt`, `igdas/robots-www.igdas.com.tr.txt`.

### Kaç istek attım

Kaynak başına: BEDAŞ 9 (robots ve API'ler dahil), AYEDAŞ 5, İSKİ 10 civarı (JS dosyalarıyla, WAF'a takılınca durdum), İGDAŞ 2 (sadece robots.txt), İBB Açık Veri 3 (robots.txt ve yukarıda anlattığım 2 API isteği).

### Veri modeli için not

Plan'daki `outage` tablosu BEDAŞ için yetiyor. İki ek öneriyorum, Faz 3'te onayınla eklerim:
- `external_id`: kaynağın kendi id'si (`plannedOutage.id`, `OUTAGE_NO`). Kaynak id veriyorsa dedup için hash'ten daha sağlam.
- `lat`, `lon`: BEDAŞ planlılarda koordinat veriyor, ileride mahalle poligonu gelene kadar nokta olarak gösterilebilir.

## İskelet

### Ne kurdum

- `services/collector` ve `services/api`: Spring Boot 4.1.1 (şu anki son kararlı sürüm), Java 21, Maven. Şimdilik sadece `spring-boot-starter-webmvc`, actuator ve Prometheus registry var. Liveness/readiness probe'ları açık (`/actuator/health/liveness`, `/actuator/health/readiness`), `/actuator/prometheus` açık. Her serviste bu üç endpoint'i gerçek HTTP ile kontrol eden bir test var.
- `frontend`: React 19 + Vite 8. Şimdilik sadece başlık ve köşede sürüm/ortam etiketi var (`VITE_APP_VERSION`, `VITE_APP_ENV` build argümanından geliyor). nginx `/api/` isteklerini api servisine proxy'liyor. `/api/stream` için buffering kapalı, SSE Faz 3'te buna ihtiyaç duyacak.
- `docker-compose.yml`: postgres 18, redis 8, collector, api, frontend. Her birinde healthcheck ve `mem_limit` var. Servisler bağımlılıkları healthy olmadan başlamıyor.
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

## Açık kararlar (senden)

1. AYEDAŞ, İSKİ ve İGDAŞ v1'den çıkarsa v1 sadece BEDAŞ (Avrupa yakası elektrik) olur. Faz 2'yi sadece BEDAŞ ile mi yapalım, yoksa v1 için başka bir kaynak mı arayalım?
2. İSKİ için gömülü token'la devam etmek ister misin? Benim önerim hayır.
3. Faz 9'daki İGDAŞ maddesinin yerine ne gelsin?
4. Veri modeline `external_id` ve `lat`/`lon` eklensin mi?
