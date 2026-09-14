# Faz 4 - Frontend

Tarih: 2026-09-14

English: [../en/04-frontend.md](../en/04-frontend.md)

Harita çalışıyor. İlçeler aktif kesinti sayısına ve türüne göre renkleniyor, ilçeye tıklayınca o ilçedeki kesintiler listeleniyor. Yeni bir kesinti veritabanına düştüğünde sayfa yenilenmeden haritada belli oluyor.

## Ne var

| Parça | Ne yapıyor |
|---|---|
| Harita | Leaflet, OpenStreetMap karoları. 973 ilçe poligonu, il sınırları kalın çizgi. İlçenin üstüne gelince adı ve seçili türlerdeki kesinti sayısı. |
| Renk | Ton türü, koyuluk sayıyı gösteriyor (1, 2-4, 5-9, 10+). Elektrik turuncu, su mavi, iki tür birden varsa mor. Doğalgazın tonu (kırmızı) hazır, kaynağı yok. |
| Tür filtresi | Elektrik ve su. Doğalgaz görünüyor ama seçilemiyor (kaynak Faz 9'da). Filtre hem renklendirmeyi hem paneli etkiliyor. |
| İlçe paneli | "Şu an süren" ve "Yaklaşan": tür, planlı/arıza, saat aralığı (Türkiye saati), mahalleler, neden, kaynak ve duyuru linki. Dar ekranda alttan açılan bir sayfa. |
| Canlı güncelleme | `/api/stream` (SSE). Olay gelince özet yeniden çekiliyor, aktif hale gelen kesintinin ilçesi yanıp sönüyor. Açık paneldeki ilçeye olay geldiyse liste yenileniyor. |
| Veri tazeliği | "Son güncelleme: 2 dk önce", yani kaynakların en son başarılı taraması. Tıklayınca kaynak bazında son tarama. Gecikmiş kaynak varsa üst çubukta "N kaynak gecikmeli" yazıyor. |
| Bağlantı durumu | Yeşil "Canlı", sarı yanıp sönen "Yeniden bağlanıyor". |
| Sürüm ve Yenilikler | Sol altta `v0.1.0 · LOCAL` (build argümanları `APP_VERSION`, `APP_ENV`). Tıklayınca "Yenilikler" penceresi açılıyor, içerik repo kökündeki `CHANGELOG.md`. |

## İlçe sınırları

İki aday vardı:
- **geoBoundaries TUR ADM2**: OpenStreetMap'ten, lisans ODbL 1.0. Veri 2023 tarihli, ilçelerde il bilgisi yok. ODbL türetilmiş veriye aynı lisansla paylaşma şartı getiriyor.
- **OCHA HDX COD-AB** ("Türkiye - Subnational Administrative Boundaries"): Harita Genel Komutanlığı'nın verisi, lisans CC BY-IGO. 973 ilçe, her ilçede il adı var, 2025'e kadar güncellenmiş.

HDX'i seçtim. CC BY-IGO atıf istiyor ve veride değişiklik yapıldıysa belirtilmesini istiyor. Haritanın köşesinde "İlçe sınırları: HGK / OCHA (CC BY-IGO)" yazıyor. Sadeleştirme ve ad düzeltmesi bu notta ve dosyanın yanındaki [`frontend/public/geo/NOTICE.txt`](../../frontend/public/geo/NOTICE.txt)'de yazılı.

HDX'teki Türkçe adlar bozuk çıktı. "ı" harfi "i" olmuş (Kadiköy, Ağri), bazı "i"lerin arkasında ayrı bir birleşik nokta (U+0307) var (Şi̇şli̇). Eşleştirme anahtarı bundan etkilenmiyor ama ekranda görünen ad yanlış olurdu. Doğru yazımı Wikidata'dan (CC0) aldım, eşleştirme anahtarla:
- 967 ilçenin adı Wikidata'dan geliyor.
- Yahyalı ve Muratlı Wikidata'da ilin alt birimi olarak yok, HDX'te de "ı" kaybolmuş. Bu ikisi script'te elle yazılı.
- Kalan 4 ilçede (Kahramankazan, Elbistan, Uşak, Ereğli) HDX'teki ad birleşik nokta atılarak kullanılıyor. Bunlarda "ı" sorunu yok.

Dosyayı [`frontend/scripts/ilceler.py`](../../frontend/scripts/ilceler.py) üretiyor. Script HDX'i ve Wikidata'yı indiriyor, adları düzeltiyor, mapshaper ile %6 sadeleştirip TopoJSON yazıyor. 29 MB'lık GeoJSON 476 KB'a iniyor, gzip ile 182 KB (nginx'te gzip açıldı). İl sınırları ayrı bir dosya değil: TopoJSON'da komşu ilçeler aynı çizgiyi paylaşıyor, iki yanında farklı il olan çizgiler il sınırı olarak çiziliyor.

## Kaynak adlarını haritaya eşleştirme

API her kesintide `ilKey`/`ilceKey` veriyor (Faz 3). Harita kimliği `IL|ILCE`, iki fark için kural var:
- Kaynaklar il merkezini `MERKEZ` diye yazıyor (Burdur, Isparta, Sivas). HDX'te merkez ilçe ilin adını taşıyor. `MERKEZ` ilin adına çevriliyor.
- HDX Gaziosmanpaşa'yı iki kelime yazıyor ("Gazi Osmanpaşa"). Anahtardaki boşluklar atılıyor.

Canlı veriyle denedim: kaynaklardan gelen 139 il/ilçe çiftinin 137'si eşleşti. Kalan ikisi:
- **ÇEDAŞ'ın serbest metinli bir kaydı** il `TOKAT.`, ilçe `.` olarak gelmiş. Harita tarafında yapılacak bir şey yok. Lejantta "Haritada yeri bulunamayan: 1 kesinti" diye sayılıyor, üstüne gelince adı görünüyor. Kesinti kaybolmuyor ama haritada yeri de yok.
- **KCETAŞ'ın Gemerek kayıtları Kayseri'ye yazılmıştı.** Gemerek Sivas'ta, KCETAŞ oraya da hizmet veriyor. Collector'da il sabit `KAYSERİ` idi. Kaynak ili adresin sonuna yazıyor (`... KÖPRÜBAŞI MAH. GEMEREK SİVAS`), parser artık ili oradan okuyor: ilçeden sonra gelen tek kelime, yoksa Kayseri. İki test eklendi, collector'da 78 test yeşil. Canlıda eski 4 Gemerek kaydı GONE oldu, aynı kayıtlar `SİVAS` ile NEW geldi ve harita özetinde Sivas/Gemerek görünüyor. Ayrıntı: [02-collector.md](02-collector.md).

## Canlı güncelleme

- `EventSource` bağlantı koparsa kendisi yeniden bağlanıp Last-Event-ID gönderiyor. Ama sunucu 200 dışında bir cevap dönerse (api kapalıyken nginx 502 veriyor) bağlantıyı kalıcı olarak kapatıyor. Bu yüzden `src/lib/live.js` o durumda bağlantıyı kendisi yeniden açıyor. Bekleme 2 sn'den başlıyor, her denemede ikiye katlanıyor, en fazla 30 sn. Son olay kimliği `?lastEventId=` ile gidiyor (api Faz 3'te bunu kabul ediyordu), kaçırılan olaylar sırayla geliyor.
- Olaylar 300 ms toplanıp tek seferde işleniyor. İlk yüklemede ya da bir tarama bitince onlarca olay art arda geliyor, her biri için ayrı iş yapmak gereksiz.
- Olayın gövdesi kesintinin tamamı. Ama özeti olaylardan hesaplamak yerine `/api/map/summary` yeniden çekiliyor (Redis'te cache'li, ucuz). Böylece istemciyle sunucu arasında sayı farkı birikmiyor. Bir olay kaçarsa diye özet ayrıca 2 dakikada bir çekiliyor.
- Vurgu: aktif hale gelen kesintinin ilçesi üç kez kırmızı kenarla yanıp sönüyor. Aktif hale gelmek yeni bir kesinti de olabilir, saati gelen planlı bir kesinti de (`TransitionSweeper`'ın `outage.updated`'ı). Biten kesintide vurgu yok, sadece renk açılıyor. `prefers-reduced-motion` açıksa tek ve yavaş bir yanma.
- Kopup geri gelince ve `outage.resync` gelince özet, kaynaklar ve açık panel tazeleniyor.

## CHANGELOG ve build

"Yenilikler" penceresi CHANGELOG'dan okuyor. CHANGELOG.md aslında Faz 5'in maddesi, ama pencere için şimdiden repo köküne `CHANGELOG.md` (Türkçe, pencere bunu okuyor) ve `CHANGELOG.en.md` ekledim. Biçim Keep a Changelog, şimdilik tek bölüm var: "Yayınlanmamış". Sürüm numaraları Faz 5'te v1.0.0 ile başlayacak.

Pencere, bu tarayıcıda görülen son sürüm (localStorage) şimdikinden farklıysa kendiliğinden açılıyor. Plan'daki demo senaryosundaki "PROD'da yeni sürüm ve Yenilikler penceresi açılır" adımı bu. İlk ziyarette de açılıyor.

Frontend imajı `CHANGELOG.md`'yi build sırasında okuyor. Bu yüzden compose'da build context `./frontend` yerine repo kökü oldu (`dockerfile: frontend/Dockerfile`). Docker'a sadece `frontend/` ve `CHANGELOG.md` gidiyor (`frontend/Dockerfile.dockerignore`). Faz 5'teki CI da aynı context'i kullanmalı.

## Neden böyle

- **react-leaflet yok**: Leaflet'i doğrudan kullandım. Harita bir kez kuruluyor, sonradan değişen tek şey 973 poligonun stili ve vurgu. Bunu ref'ler ve `setStyle` ile yapmak react-leaflet'in bileşen modelinden daha basit.
- **Markdown kütüphanesi yok**: CHANGELOG'dan sadece sürüm başlıkları, grup başlıkları ve madde satırları okunuyor, 40 satırlık bir ayrıştırıcı yetti. `innerHTML` kullanılmıyor.
- **TopoJSON**: komşu ilçelerin ortak sınırı bir kez yazılıyor, dosya küçülüyor. İl sınırları da ek veri olmadan çıkıyor. `topojson-client` küçük bir kütüphane.
- **Karo sunucusu**: OpenStreetMap'in karo sunucusu. Kullanım politikası küçük projelere izin veriyor, atıf köşede. Gerçek trafik gelirse başka bir sağlayıcı ya da kendi karo sunucumuz gerekebilir. Faz 9'daki k6 testi api'yi ölçüyor, karoları değil.
- **Saatler Türkiye saatinde**: tarayıcı başka bir saat diliminde olsa da saatler `Europe/Istanbul` ile gösteriliyor.

## Testler

`npm test` (Vitest, jsdom, Testing Library): 34 test, hepsi yeşil. `npm run build` de yeşil (JS 391 KB, gzip ile 121 KB).
- İlçe kimliği, `MERKEZ` ve boşluk kuralı, api ile aynı ad anahtarı (Türkçe büyük harf, U+0307)
- Renk kademeleri ve türe göre ton
- Göreli zaman ve Türkiye saatiyle saat aralığı
- CHANGELOG ayrıştırma
- SSE bağlantısı:
  - olayları iletiyor
  - tarayıcının kendi yeniden bağlanmasına karışmıyor
  - kalıcı kopmada son kimlikle yeniden açıyor, beklemeyi artırıyor
  - durdurulunca yeniden denemiyor
- Bileşenler:
  - bağlantı rozeti
  - veri tazeliği ve gecikmiş kaynak
  - tür filtresi
  - lejanttaki eşleşmeyen kesinti sayısı
  - ilçe paneli: aktif ve yaklaşan kesintiler, duyuru linki, api'ye giden sorgular, filtre dışında kalan sayı, yeniden yükleme
  - Yenilikler penceresinin ne zaman açıldığı

Leaflet'in kendisi jsdom'da test edilmiyor. Haritayı compose'da tarayıcıyla denedim.

## Elle deneme (compose, canlı kaynaklar)

Stack'i canlı kaynaklarla ayağa kaldırıp arayüzü headless Chromium ile denedim (Playwright, repo dışında bir script).
- 973 ilçe çizildi, o an 57 ilçe renkliydi. İlk açılışta Yenilikler penceresi açıldı, kapatınca bir daha açılmadı.
- Pınarbaşı'na tıklayınca panel açıldı: şu an süren kesinti yok, yaklaşan 27 KCETAŞ kesintisi var. Hepsinde mahalle, saat ve duyuru linki var.
- **Sayfa yenilemeden vurgu**: Redis Stream'e İzmir/Bornova için sahte bir aktif su kesintisi yazdım. Bornova 511 ms'de yanıp sönmeye başladı, 545 ms'de rengi açık maviye döndü. GONE yazınca 528 ms'de rengi gitti. Sayfa hiç yenilenmedi. Bu sürelere olayın `docker compose exec` ile yazılması ve 300 ms'lik toplama da dahil.
- **API kapatılınca**: `docker compose stop api` sonrası rozet "Yeniden bağlanıyor" oldu. Ölçülen 10,5 sn'nin çoğu api'nin kapanmasını beklemek. `docker compose start api` sonrası 8,7 sn'de tekrar "Canlı". Konsoldaki hatalar sadece bu arada gelen 502'ler ve kesilen SSE akışı.
- **Mobil (390 px)**: yatay kaydırma yok, panel alttan açılıyor, seçilen ilçe panelin üstünde kalan alana sığdırılıyor.

## Nerede takıldım

- **Karoların arasında beyaz çizgiler**: `zoomSnap: 0.25` ile Türkiye ekrana tam oturuyordu ama kesirli zoom'da karoların arasında ince boşluklar kaldı. Tam sayı zoom'a döndüm. Dar ekranda Türkiye kenarlardan biraz taşıyor.
- **Mobilde atıf sürüm etiketini kapattı**: atıf dar ekranda iki satıra iniyor. Sürüm etiketi ve lejant onun üstüne alındı.
- **Mobilde seçilen ilçe panelin altında kaldı**: panel haritanın alt %60'ını kapatıyor. Dar ekranda seçilen ilçe artık üstte kalan alana sığdırılıyor.
- **CSS'teki bir yazım hatası build'i kırdı**: `max width:` diye bir satır kalmıştı. Testler geçti, Vite'in CSS küçültücüsü hata verdi.
- **Wikidata'nın ilk sorgusu eksik kaldı**: ilçeleri tipine (P31) göre sorgulayınca 612 ilçe gelmedi, Wikidata'da ilçelerin tipi tutarlı değil. İllerin alt birimleri (P150) üzerinden sorgulayınca 6'ya indi.

## Bilinen sınırlar

- ÇEDAŞ'ın bozuk adla gelen kaydı haritada yok, sadece lejantta sayılıyor. Collector'da ÇEDAŞ'ın serbest metin biçimi hâlâ "en iyi çaba".
- İSKİ verisi geçmiş veri. Haritada da panelde de görünmüyor, çünkü panel sadece süren ve yaklaşan kesintileri gösteriyor. Şu an "Su" katmanında sadece İZSU var. İSKİ verisinin yeri v2.1'deki mahalle karnesi.
- Harita ilçe seviyesinde, mahalle poligonu yok (plan'da sonraya bırakılmıştı).
- Karolar OpenStreetMap'ten geliyor. Yoğun trafikte başka bir sağlayıcı gerekecek.
- Tarayıcıyla yapılan deneme repoda ve CI'da değil. Faz 5'te CI'a eklenebilir.
- Dar ekranda (390 px) Türkiye zoom 5'te kenarlardan biraz taşıyor.
- Masaüstünde karo çizgileri tam sayı zoom'la gitti. Headless Chromium'da 2x piksel yoğunluklu mobil görüntüde karo sınırlarında hâlâ ince çizgiler var. Gerçek bir telefonda denemedim.
