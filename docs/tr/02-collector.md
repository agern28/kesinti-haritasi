# Faz 2 - Collector

Tarih: 2026-09-13

English: [../en/02-collector.md](../en/02-collector.md)

Collector artık altı kaynağı kendi zamanlamasıyla tarıyor, veriyi ortak modele çeviriyor ve sadece değişenleri Redis Stream'e yazıyor. Kaynak listesi Faz 1'deki kararlardan geliyor ([01-kaynak-taramasi.md](01-kaynak-taramasi.md), [fazlar.md](fazlar.md)).

## Kaynaklar ve zamanlama

| Feed | Ne | İstek | Aralık |
|---|---|---|---|
| `BEDAS/planned` | `GET www.bedas.com.tr/GetItemsData` | 1 | 15 dk |
| `BEDAS/unplanned` | `GET kesintiapi.ckenerji.com.tr/BEDAS/RetrieveOutages` + yeni trafolar için `GetLocation` | 1 + en çok 40 | 5 dk |
| `AEDAS/planned`, `AEDAS/unplanned` | Aynı altyapı, `www.akdenizedas.com.tr` ve `/AEDAS/...` | aynı | 15 dk / 5 dk |
| `CEDAS/planned`, `CEDAS/unplanned` | Aynı altyapı, `www.cedas.com.tr` ve `/CEDAS/...` | aynı | 15 dk / 5 dk |
| `KCETAS/planned` | `POST www.kcetas.com.tr/kesinti-sorgu.php`, bugün + 2 gün | 3 | 15 dk |
| `IZSU/all` | `GET izsu.gov.tr/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama` | 1 | 5 dk |
| `ISKI/daily` | İBB veri seti sayfası, dosya linkleri değiştiyse xlsx'ler | 1 (+2) | 24 saat |

- İZSU planlı çalışma ile arızayı aynı sayfada veriyor. Kural gereği ("bir kurum ikisini tek sayfada veriyorsa o sayfa 5 dakikada taranır") tek feed olarak 5 dakikada taranıyor.
- Aralıklar `collector.feeds.<kaynak>-<feed>.interval` ile değişiyor, `enabled: false` ile tek bir feed kapanıyor. Kodda alt sınır var: config'e 1 dakika yazılsa da en sık 5 dakikada bir taranıyor.
- Fixed delay kullanıyorum, yani bir tarama bitmeden aynı feed'in sonraki taraması başlamıyor.
- Uygulama açılırken ilk taramalar 0-30 saniyeye dağıtılıyor. Her taramanın başında da 0-5 saniye rastgele bekleme (jitter) var. Böylece dokuz feed aynı saniyede başlamıyor.

## Nasıl çalışıyor

```
ScanScheduler (feed başına ayrı görev)
   -> ScanRunner: jitter -> SourceCollector.collect() -> Differ -> StreamPublisher -> snapshot kaydet
SourceCollector -> PoliteHttpClient (robots.txt, host başına sıra ve bekleme, User-Agent)
```

- **`SourceCollector`**: `id()`, `defaultInterval()`, `collect()`. Yeni kurum eklemek, yeni bir sınıf ve onun fixture testini yazmak demek. CK Enerji'nin üç şirketi aynı sınıfları parametreyle kullanıyor (`CkCompany` enum'ı).
- **`Outage`**: ortak model, alanları [veri-modeli.md](veri-modeli.md)'deki tabloyla aynı. `dedup_key` ve içerik hash'i `OutageKeys`'te.
- **`Differ`**: önceki snapshot (`dedup_key -> içerik hash'i`) ile yeni taramayı karşılaştırıyor. Yeni anahtar NEW, hash'i değişen UPDATED, kaybolan GONE oluyor. Değişiklik yoksa olay listesi boş kalıyor ve stream'e hiçbir şey gitmiyor.
- **Snapshot**: Redis'te `collector:snapshot:<kaynak>:<feed>` hash'inde duruyor. Önce geçici anahtara yazılıp sonra `RENAME` ediliyor, böylece okuyan kimse yarım snapshot görmüyor.
- **Stream**: `outage-events`, `MAXLEN ~ 100000`. Alanlar: `event` (NEW/UPDATED/GONE), `source`, `feed`, `dedupKey`, `contentHash`, `scannedAt`, `payload` (Outage JSON, GONE'da boş). Faz 3'teki api bunu okuyacak.
- **Sıra**: önce olaylar yazılıyor, sonra snapshot kaydediliyor. Arada collector çökerse aynı olaylar bir sonraki taramada tekrar gider (at-least-once). API tarafında `dedup_key` ile upsert yapılacağı için bu zararsız.

## Hata izolasyonu

- Her feed ayrı bir zamanlanmış görev. Birinin hatası ya da takılması diğerlerini etkilemiyor.
- `collect()` hata fırlatırsa `collector_errors_total` artıyor, snapshot'a dokunulmuyor. Bu önemli: İZSU'nun sayfa yapısı değişip tablo bulunamazsa parser boş liste dönmüyor, hata veriyor. Aksi halde bütün kayıtlar bir anda GONE olurdu.
- Aynı sebeple KCETAŞ `success: false` ya da `sistem_bakimda: true` dönerse ve İBB sayfasında xlsx linki bulunamazsa hata veriliyor. Kaynakta gerçekten kesinti yoksa boş liste normal kabul ediliyor.

## Kaynaklara nezaket

Bütün istekler `PoliteHttpClient`'tan geçiyor:
- Her istekten önce host'un robots.txt'i kontrol ediliyor (RFC 9309). 4xx kısıt yok demek; 5xx ya da hiç ulaşılamaması tamamen yasak demek. Kurallar 24 saat, ulaşılamama durumu 10 dakika cache'leniyor.
- Eşleştirmede `*` ve `$` destekleniyor, en uzun kural kazanıyor, eşitlikte Allow.
- Yönlendirmeler elle izleniyor. Her adımda hedefin robots.txt'i yeniden kontrol ediliyor, yasak bir yola yönlendirilirsek istek hiç gitmiyor.
- Aynı host'a istekler sırayla gidiyor, aralarında en az 2 saniye var. `Crawl-delay` daha uzunsa ona uyuluyor: İBB için 10 saniye.
- User-Agent: `KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)`.
- **CK Enerji trafo konumları**: arıza satırlarında yer adı yok, trafo numarası var. Trafo → ilçe/mahalle eşlemesi Redis'te 30 gün tutuluyor, bulunamayanlar 1 gün. Tarama başına en çok 40 yeni sorgu atılıyor. Konumu henüz bilinmeyen arıza o taramada atlanıyor, sonraki taramalarda geliyor.
- **İBB**: günde bir sadece veri seti sayfası okunuyor. xlsx dosyaları sadece link listesi değiştiyse indiriliyor. CKAN API'sine (`/api/`, robots.txt'e göre yasak) hiç gidilmiyor.

## Ortak model ve kimlik

| Kaynak | external_id | Not |
|---|---|---|
| BEDAŞ/AEDAŞ/ÇEDAŞ planlı | `plannedOutage.id`; kayıt birden fazla ilçeye yayılıyorsa `id/İLÇE` | Her ilçe ayrı kayıt |
| BEDAŞ/AEDAŞ/ÇEDAŞ arıza | `OUTAGE_NO/İLÇE` | Arıza birden fazla ilçeye yayılabiliyor ve trafo konumları taramalar arasında parça parça bulunuyor. İlçe kimliğin parçası olunca bir ilçenin sonradan eklenmesi diğerini değiştirmiyor. |
| KCETAŞ, İZSU, İSKİ | yok | `dedup_key` hash'le (kaynak, ilçe, başlangıç, sıralı mahalleler) |

## Normalizasyon

- **Adlar**: boşluklar teke iniyor, Türkçe kurallarıyla büyük harfe çevriliyor (i → İ, ı → I), mahalle adının sonundaki `MAH.`, `MAH`, `MH.`, `MAHALLESİ` atılıyor. Kaynaklar arası eşleştirme için ayrıca bir anahtar var (`Names.key`): Türkçe karakterleri ASCII'ye katlıyor, `YUSUFELİ` ile `YUSUFELI` aynı anahtarı veriyor.
- **Tarih/saat**: saat dilimi olmayan değerler Europe/Istanbul kabul ediliyor. Kaynakların biçimleri: `2026-09-10 09:00:00` (CK), ISO ofsetli (CK arıza), ISO ofsetsiz (KCETAŞ), `10.09.2026 - 22:00` (İZSU), `12/02/2024 13:30:10` ve Excel seri sayısı (İBB).
- **Mahalleler, BEDAŞ**: `İSTANBUL ESENLER ilce MERKEZ-ORUÇREİS mah ALBAYRAK sk / TURGUT REİS mah ... sk bölgelerinde`. Segmentler ` / ` ile ayrılıyor, `mah`'tan önceki kısım alınıyor, `MERKEZ-` öneki atılıyor. `- mah  sk` gibi boş kayıtlar atlanıyor.
- **Mahalleler, AEDAŞ/ÇEDAŞ**: `ANTALYA,AKSU,MERKEZ ALTINTAŞ Mah. 31225,...;ANTALYA,MURATPAŞA,...`. `;` ilçe gruplarını ayırıyor. `Mah.` işaretli olanlar güvenilir sayılıyor. İşaretsiz bir aday, güvenilir bir mahalle adıyla başlıyorsa sokak kalıntısı sayılıp atılıyor (`MERKEZ DUACI 9035`).
- **Mahalleler, ÇEDAŞ serbest metin**: bazı mesajlar `il,ilçe` öneki olmadan geliyor (`ÖZÜKAVAK KASABASI, KURTAĞILLI, ...`), bazılarının arkasında trafo notu var (`...KÖYLERİ<TAB>T_TRANSFORMATOR_DAGITIM : ...`). Önek yoksa kayıt ilk ilçeye yazılıyor, trafo notu kesiliyor.
- **Arızada il**: GetLocation sadece ilçe ve mahalle veriyor. BEDAŞ'ta il hep İstanbul. AEDAŞ'ta birden fazla ilde geçen adları API kendisi `BURDUR_MERKEZ` gibi il önekiyle veriyor. Önek yoksa ilçe şirketin illerinin ilçe listesinde aranıyor. AKSU ve KEMER hem Antalya'da hem Isparta/Burdur'da var; önek gelmezse Antalya kabul ediliyor.

## Metrikler

| Metrik | Tür | Anlamı |
|---|---|---|
| `collector_last_success_timestamp{source,feed}` | gauge | Son başarılı taramanın unix zamanı |
| `collector_items_total{source,feed}` | sayaç | Taramalarda bulunan kayıt sayısı (kümülatif) |
| `collector_errors_total{source,feed}` | sayaç | Başarısız tarama sayısı |
| `collector_items_last_scan{source,feed}` | gauge | Son taramada bulunan kayıt sayısı |
| `collector_events_total{source,feed,event}` | sayaç | Stream'e yazılan NEW/UPDATED/GONE |
| `collector_scan_duration_seconds{source,feed}` | timer | Tarama süresi |

**Plandan küçük bir sapma, onayını istiyorum:** plan metrikleri sadece `{source}` etiketiyle tanımlıyor. Ben yanına `feed` etiketini de ekledim. Faz 8'deki alarm arıza sayfaları için 30 dakika, planlı sayfalar için 3 saat eşik istiyor. Aynı kaynağın iki feed'ini ayırmadan bu alarm yazılamıyor. `source` etiketi aynen duruyor, sadece ek etiket var. Son iki metrik de panel için ek.

Seriler uygulama açılırken kayıt ediliyor. Böylece ilk taramadan önce de görünüyorlar (`last_success` 0). Faz 8'de alarm yazarken bunu hesaba katmak gerekecek.

## Neden böyle

- **Apache POI yok**: İBB dosyasını okumak için 150 satırlık bir xlsx okuyucu yazdım (zip + StAX, sadece ilk sayfa ve hücre metinleri). POI'nin bağımlılıkları ve bellek kullanımı, 4 GB'lık sunucu için bu kadar küçük bir iş karşılığında fazla. DTD ve dış entity kapalı, girdi boyutuna üst sınır var.
- **jsoup**: İZSU tablosu ve İBB sayfasındaki linkler için. Küçük ve bağımlılıksız.
- **Jackson 3**: Spring Boot 4 ile gelen sürüm. Uygulama kendi `JsonMapper`'ını tanımlıyor, Instant'lar ISO-8601 string olarak yazılıyor.
- **Tek replika**: Collector'da dağıtık kilit yok. Kubernetes'te `replicas: 1` çalışacak (Faz 7). İki kopya çalışırsa kaynaklara çift istek gider.

## Testler

Toplam 73 test, hepsi yeşil (`mvn verify`). Hiçbiri canlı siteye gitmiyor.
- **Parser testleri, fixture'larla**: BEDAŞ (218 kayıt), AEDAŞ (225 kayıt, 236 kesinti), ÇEDAŞ (86 kayıt, 102 kesinti), CK arıza ve konum cevapları, KCETAŞ (26 özellik, 24 tekil), İZSU, İBB xlsx (6.410 satır) ve veri seti sayfası.
- **Normalizasyon ve kimlik**: ad, mahalle eki, tarih biçimleri, `dedup_key`, içerik hash'i.
- **robots.txt**: keşifte gördüğüm gerçek dosyalarla. `PoliteHttpClient` yerel bir HTTP sunucusuyla test ediliyor: yasak yola istek gitmiyor, yasak yola yönlendirme izlenmiyor, robots 5xx olunca hiç istek yok, bekleme ve Crawl-delay uygulanıyor, gzip açılıyor.
- **Diff ve tarama**: NEW/UPDATED/GONE, değişiklik yoksa yazma yok, hatada snapshot korunuyor, bir kaynağın hatası diğerini etkilemiyor, "kaynak değişmedi" yolu, zamanlama alt sınırı.
- **Gerçek Redis ile (Testcontainers)**: KCETAŞ fixture'ı iki kez taranıyor. İlk taramada 24 NEW, ikincide stream uzunluğu değişmiyor. Bir kayıt değişip bir kayıt kaybolunca UPDATED ve GONE geliyor.
- **Uygulamanın tamamı**: health, liveness/readiness (readiness Redis'e bağlı) ve `/actuator/prometheus`'ta kaynak bazında metrikler.

## Canlı deneme (compose)

`docker compose up redis collector` ile collector'ı canlı kaynaklara karşı çalıştırdım ve iki tur bekledim (yaklaşık 9 dakika). Tempo üretimdekiyle aynıydı, ek istek atılmadı.

İlk tur:

| Feed | Kayıt | NEW | Süre |
|---|---|---|---|
| BEDAS/planned | 250 | 250 | 6 sn |
| BEDAS/unplanned | 1 | 1 | 71 sn |
| AEDAS/planned | 231 | 231 | 7 sn |
| AEDAS/unplanned | 2 | 2 | 126 sn |
| CEDAS/planned | 135 | 135 | 4 sn |
| CEDAS/unplanned | 0 | 0 | 10 sn |
| KCETAS/planned | 92 (6 tekrar) | 86 | 12 sn |
| IZSU/all | 0 | 0 | 9 sn |
| ISKI/daily | 25.646 (7.266 tekrar) | 18.380 | 47 sn |

- Hata sayısı bütün feed'lerde 0. robots.txt: İBB ve İZSU'da kurallı dosya var (200), diğerlerinde yok (404).
- Stream'e 19.085 olay düştü. Bunların 18.380'i İSKİ'nin geçmiş verisi: iki xlsx dosyası toplam 25.646 satır, 7.266 satır iki dosyada ortak ya da aynı kaydın tekrarı.
- İZSU o an 0 kayıt verdi. Tablo bulundu ama içinde satır yoktu, fixture'daki deplase çalışması bitmiş. Tarama hata vermediği için tablonun bulunduğunu biliyoruz.
- Arıza feed'leri uzun sürdü (AEDAŞ 126 sn), çünkü ilk turda trafo konumları soruldu. Tarama başına 40 sorgu sınırı var, istekler arasında 2 sn bekleniyor ve üç şirketin arıza feed'i aynı host'u (`kesintiapi.ckenerji.com.tr`) sırayla kullanıyor. Konumlar cache'e girdikten sonra BEDAŞ'ın ikinci taraması 5 sn sürdü.

İkinci tur (5 dakikalık feed'ler):

| Feed | Kayıt | NEW | UPDATED | GONE |
|---|---|---|---|---|
| IZSU/all | 0 | 0 | 0 | 0 |
| CEDAS/unplanned | 0 | 0 | 0 | 0 |
| BEDAS/unplanned | 1 | 0 | 0 | 0 |
| AEDAS/unplanned | 2 | 1 | 1 | 1 |

- Değişmeyen üç feed stream'e hiçbir şey yazmadı. Stream sadece AEDAŞ'ın 3 olayı kadar büyüdü (19.085 -> 19.088).
- AEDAŞ'taki değişiklikler gerçek. Konumlar cache'te olduğu için GONE ancak bir arıza kaynaktan kalkınca oluşabiliyor. NEW ve UPDATED yeni bir arızadan ya da bu turda konumu bulunan trafolardan gelebilir; ilk turda 40 sorgu sınırına takılan trafolar ikinci turda soruldu.
- Bellek: İBB'nin 25 bin satırı işlendikten sonra collector 267 MiB / 384 MiB, Redis 24 MiB. Faz 7'de collector'a 512 Mi limit vermeyi öneriyorum. İBB dosyası akış halinde okunarak bellek düşürülebilir ama günde bir çalıştığı için şimdilik gerek görmedim.

## Nerede takıldım

- AEDAŞ ve ÇEDAŞ arızalarında il bilgisi yoktu. İlk çözüm statik ilçe listesiydi ama "MERKEZ" üç ilde birden var. İki GetLocation örneği çekince API'nin bu durumda `BURDUR_MERKEZ` döndüğünü gördüm, sorun büyük ölçüde kendiliğinden çözüldü.
- ÇEDAŞ mesajları üç farklı biçimde geliyor, biri serbest metin. Parser burada "en iyi çaba" ile çalışıyor. Testler bilinen kayıtları sabitliyor. Yeni bir biçim görürsek fixture ekleyip testi genişletmek gerekecek.
- KCETAŞ aynı mahalle ve saat için birden fazla trafo satırı veriyor (26 satır, 24 tekil). Bunları Differ tekilleştiriyor.

## Bilinen sınırlar

- CK'da devam eden planlı kesinti hem planlı listede hem arıza listesinde (`Bildirimli`) görünüyor. Arızalardan sadece `Bildirimsiz` satırları alıyorum, iki kez sayılmıyor. Ama planlı kesintinin gerçekte ne zaman bittiğini arıza tarafından öğrenmiyoruz.
- İZSU'da planlı/arıza ayrımı iş adında "arıza" geçmesine bakıyor. Sezgisel bir kural.
- Hash kimlikli kaynaklarda (KCETAŞ, İZSU, İSKİ) başlangıç saati değişirse kayıt NEW + GONE olarak görünüyor. Veri modelinde bilerek kabul ettiğimiz bir durum.
- İBB verisi geçmiş veri (2022-2024). İlk taramada binlerce NEW üretiyor ama haritada aktif kesinti olarak görünmeyecek. v2.1 mahalle karnesi için toplanıyor.
