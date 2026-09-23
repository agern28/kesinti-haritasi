# Faz 3 - API

Tarih: 2026-09-13

English: [../en/03-api.md](../en/03-api.md)

API artık collector'ın Redis Stream'e yazdığı olayları okuyup PostgreSQL'e yazıyor ve REST ile SSE üzerinden sunuyor. Veritabanına yeni, değişen ya da biten bir kesinti düştüğü an bağlı tarayıcılara olay gidiyor. Birden fazla pod çalışsa da bütün istemciler olayı alıyor.

## Uç noktalar

| Uç nokta | Ne döner |
|---|---|
| `GET /api/outages` | Kesinti listesi. Filtreler: `type`, `source`, `il`, `ilce`, `active`; sayfalama `page`, `size` (en çok 500). En yeni başlangıç önce. |
| `GET /api/outages/{id}` | Tek kesinti, yoksa 404 |
| `GET /api/map/summary` | İlçe bazında aktif kesinti sayıları: toplam, planlı/arıza, türe göre. Redis'te cache'li. |
| `GET /api/sources` | Her kaynağın son başarılı taraması, feed'leri ve gecikmiş olup olmadığı |
| `GET /api/stream` | SSE: `outage.created`, `outage.updated`, `outage.ended`, `outage.resync` |
| `/swagger-ui.html` | Swagger UI, bütün REST uç noktalarını tarayıcıdan denemek için |
| `/canli.html` | SSE akışını canlı gösteren küçük sayfa: bağlantı durumu, son olay id'si, gelen olaylar |
| `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` | Probe'lar ve metrikler |

`il` ve `ilce` filtreleri Türkçe karakter ve büyük/küçük harf farkını gözetmiyor: `ilce=sisli`, `ŞİŞLİ` ve `Şişli` aynı sonucu veriyor.

## Arayüz

Faz 3'e başlarken "bir GUI'si olsun" dendi. Asıl harita arayüzü Faz 4'te, fazların sırasını bozmamak için bunu API'nin kendi arayüzü olarak yorumladım:
- **Swagger UI** (`/swagger-ui.html`): springdoc ile. Endpoint'lerin açıklamaları ve parametre örnekleri kodda.
- **Canlı olay sayfası** (`/canli.html`): API'nin içinde duran tek bir HTML dosyası. `EventSource` ile bağlanıyor, bağlı / yeniden bağlanıyor durumunu ve gelen olayları tablo halinde gösteriyor. Swagger UI SSE akışını gösteremediği için gerekiyordu. SSE hattını elle denerken de işe yarıyor.

Swagger UI üretimde açık kalsın mı, Faz 7'de Helm values'ında karar vereceğiz (`springdoc.swagger-ui.enabled`).

## Akış

```
collector -> Redis Stream "outage-events"
   -> OutageStreamConsumer (consumer group "api", pod başına bir consumer)
   -> OutageEventProcessor: PostgreSQL upsert / gone_at
        -> SummaryCache.refresh(ilçe)
        -> LiveEvents: XADD "sse-events" (olay kimliği) + PUBLISH "outage-updates"
   -> her pod: Pub/Sub dinleyicisi -> SseHub -> o pod'a bağlı tarayıcılar
TransitionSweeper (dakikada bir, tek pod): saati gelen planlı kesinti başladı/bitti -> aynı yayın yolu
```

### Stream tüketimi

- Consumer group `api`. Her pod bir consumer; adı `HOSTNAME`, Kubernetes'te pod adı oluyor. Bir mesaj tek pod'a gidiyor.
- Grup `0`'dan oluşturuluyor. API ilk kez açıldığında collector'ın o ana kadar yazdığı her şeyi işliyor.
- Mesaj veritabanına yazıldıktan sonra onaylanıyor (XACK). Yazılamazsa onaylanmıyor. 60 saniye bekleyen mesajı (pod ölmüş olabilir) başka bir pod devralıyor (XCLAIM) ve yeniden deniyor. Pod yeniden başlayınca da önce kendi adına bekleyen mesajları işliyor.
- Bozuk mesaj (eksik alan, geçersiz JSON, bilinmeyen tür) tekrar denemekle düzelmeyeceği için onaylanıp atlanıyor ve `api_stream_events_total{result="bad"}` sayılıyor.

### Upsert ve tekilleştirme

`dedup_key` üzerinde `INSERT ... ON CONFLICT DO UPDATE ... WHERE`:
- Satır sadece içerik hash'i değiştiyse ya da kayıt kaynaktan kalkıp geri geldiyse güncelleniyor. Collector aynı olayı ikinci kez yazarsa (at-least-once) veritabanı değişmiyor, SSE olayı da gitmiyor.
- Olayın `scannedAt`'i satırdaki `last_seen_at`'ten eskiyse uygulanmıyor. Devralınan eski bir mesaj yeni veriyi ezmiyor.
- `RETURNING ..., (xmax = 0)` ile satırın yeni eklendiği mi güncellendiği mi anlaşılıyor: `outage.created` ya da `outage.updated`.
- GONE gelince satır silinmiyor, `gone_at` dolduruluyor ve `outage.ended` gidiyor.

### SSE ve pod'lar arası dağıtım

- Olayı işleyen pod iki şey yapıyor:
  - Olayı `sse-events` stream'ine yazıyor. Stream id'si olayın kimliği oluyor, son 10.000 olay tutuluyor.
  - Aynı olayı `outage-updates` Pub/Sub kanalına gönderiyor.
- Bütün pod'lar kanalı dinliyor ve olayı kendi bağlı istemcilerine iletiyor. Olayı işleyen pod da kendi istemcilerine Pub/Sub üzerinden gönderiyor, doğrudan göndermiyor. Böylece olay iki kez gitmiyor.
- **Last-Event-ID**: tarayıcı bağlantı koparsa `EventSource` son aldığı id ile yeniden bağlanıyor. Pod kaçırılan olayları `sse-events`'ten okuyup sırayla gönderiyor. İstemcinin son gördüğü olay artık tutulmuyorsa `outage.resync` gidiyor; frontend listeyi baştan yükleyecek. Başlık yerine `?lastEventId=` parametresi de kabul ediliyor.
- Her istemci en son aldığı id'yi tutuyor. Kaçırılanlar gönderilirken araya giren canlı olaylar sırasını bekliyor. Aynı olay iki kez ya da geri sırada gitmiyor.
- **Heartbeat**: 15 saniyede bir SSE yorum satırı (`:hb`). Proxy'ler boşta kalan bağlantıyı kesmiyor, gönderilemeyen istemci temizleniyor.
- Bağlantı 30 dakikada bir kapanıyor ve istemci Last-Event-ID ile yeniden bağlanıyor. Uzun ömürlü bağlantılar böylece tazelenmiş oluyor.
- nginx arkasında tamponlama olmasın diye `X-Accel-Buffering: no` başlığı gidiyor. Frontend'in nginx ayarında `/api/stream` için buffering zaten kapalı.

### Zamanla değişen durum (planda yoktu)

"Kesinti bittiği an tarayıcıya gitsin" kuralı sadece kaynaktan gelen olaylarla karşılanmıyor. BEDAŞ bir planlı kesintiyi bitiş saati geçtikten sonra da bir gün listede tutuyor. Saat 17:00'de biten kesinti için collector'dan hiçbir olay gelmiyor.

Bunun için `TransitionSweeper` ekledim. Dakikada bir, son çalışmadan bu yana başlama saati gelenler için `outage.updated`, bitiş saati gelenler için `outage.ended` yayınlıyor ve o ilçelerin özetini güncelliyor. Birden fazla pod varken Redis kilidiyle aynı anda tek pod çalıştırıyor.

### Harita özeti cache'i

- `api:summary` hash'i (alan `İL_KEY|İLÇE_KEY`, değer JSON) ve `api:summary:ready` bayrağı.
- Bayrak yoksa (ilk istek ya da 10 dakikalık TTL doldu) özet veritabanından baştan hesaplanıyor. Bir kesinti değişince sadece o ilçenin alanı yeniden hesaplanıyor.
- Hit/miss `api_summary_cache_total{result}` ile sayılıyor. Faz 8'deki "cache hit" paneli buradan gelecek.

### Kaynak durumu

Collector'a küçük bir ekleme yaptım: her taramadan sonra Redis'te `collector:status` hash'ine feed'in durumunu yazıyor (alan `KAYNAK/feed`, JSON: aralık, son başarılı tarama, son kayıt sayısı, son hata). API bunu okuyup kaynak bazında topluyor.

Bir feed şu durumlarda "gecikmiş" (`stale`) sayılıyor: hiç başarılı taraması yoksa ya da son başarılı taramadan bu yana `aralık x 2 + 60 sn` geçmişse. Kaynaklar sabit bir sırayla dönüyor, görünen adları (BEDAŞ, İZSU...) ve bölgeleri API'de.

## Veritabanı

Flyway migration'ı: `V1__create_outage.sql`. Tablo [veri-modeli.md](veri-modeli.md)'deki haliyle. **Plana ek kolonlar:**
- `content_hash`: collector'ın içerik hash'i. Tekrar gelen olayın bir şey değiştirip değiştirmediğini buna bakarak anlıyoruz.
- `gone_at`: kaynak kaydı listeden kaldırdığı an. Aktif kesinti tanımı buna göre güncellendi: `starts_at <= now < ends_at` ve `gone_at` boş. Arızalar tahmini bitişten önce giderildiğinde kaynaktan kalkıyor; haritada aktif görünmemeleri gerekiyor.
- `il_key`, `ilce_key`: Türkçe karakterden bağımsız anahtar (collector'daki `Names.key` ile aynı kural). Filtrelerde ve Faz 4'te harita sınırlarıyla eşleştirmede kullanılacak.

Veri erişimi JPA değil, `JdbcClient` ile. `text[]` kolonu ve `ON CONFLICT ... WHERE ... RETURNING` upsert'i JPA'da dolambaçlı oluyor. Tek tablo için ORM gerek görmedim.

## Metrikler

HTTP metriklerine (`http_server_requests_*`) ek olarak:

| Metrik | Anlamı |
|---|---|
| `api_stream_events_total{event,result}` | İşlenen olaylar; result: applied / noop / bad / error |
| `api_sse_clients` | Bu pod'a bağlı SSE istemcisi |
| `api_sse_events_sent_total` | İstemcilere gönderilen olay |
| `api_sse_delivery_seconds` | Olayın veritabanına yazılmasından istemciye gönderilmesine kadar geçen süre (histogram) |
| `api_summary_cache_total{result}` | Özet cache hit/miss |

Faz 8'deki "SSE bağlı istemci sayısı" ve "olayın veritabanından tarayıcıya ulaşma süresi" panelleri bunlardan çizilecek. Gecikme sunucu tarafında, olayın istemciye yazıldığı ana kadar ölçülüyor; ağdaki süre dahil değil.

Readiness veritabanına ve Redis'e bağlı, liveness değil. Veritabanı kısa süre giderse pod trafik almayı bırakıyor ama yeniden başlatılmıyor.

## Testler

API'de 24 test var, hepsi yeşil (`mvn verify`). Hepsi gerçek PostgreSQL 18 ve Redis 8 ile çalışıyor (Testcontainers) ve aynı Spring context'ini paylaşıyor. Testler birbirini etkilemesin diye her test kendi il/ilçe/anahtar değerlerini üretiyor.
- **Upsert kuralları**: yeni kayıt, aynı içerik (değişiklik yok), değişen içerik, eski tarihli olay, GONE, ikinci GONE, geri gelen kayıt.
- **Filtreler**: Türkçe karakter ve büyük/küçük harf, tür, aktif, sayfalama. Hatalı istekler 400/404 dönüyor.
- **Stream tüketimi**:
  - Collector biçiminde yazılan olay veritabanında satır oluşturuyor.
  - Aynı olay tekrar gelince satır sayısı değişmiyor.
  - GONE doğru işleniyor.
  - Bozuk olay onaylanıp atlanıyor, bekleyen mesaj kalmıyor.
- **SSE**:
  - created ve ended olayları geliyor, heartbeat geliyor.
  - Last-Event-ID ile kaçırılan iki olay sırayla geliyor, zaten görülen olay tekrar gelmiyor.
  - Çok eski id ile bağlanınca `outage.resync` geliyor.
  - Zaten bitmiş kesinti yayınlanmıyor.
- **İki API örneği**: aynı JVM'de ikinci bir uygulama başlatılıyor. Olay tek örnek tarafından işleniyor, iki örneğe bağlı istemciler de olayı aynı id ile alıyor, veritabanında tek satır oluyor.
- **Harita özeti**: ilk istek miss, sonrakiler hit. Yeni kesinti ve GONE sonrası ilçe, cache yeniden kurulmadan güncelleniyor. Gelecekteki kesinti sayılmıyor.
- **Kaynaklar**: son tarama, gecikme, sıralama ve hiç durumu olmayan kaynak.
- **Zaman taraması**: başlama saati gelen kesinti için `outage.updated`, bitiş saati gelen için `outage.ended` gidiyor. Kilit başka turdayken ikinci çalışma olmuyor.
- **Diğer**: health/readiness, Prometheus metrikleri, `/v3/api-docs`, Swagger UI ve `/canli.html`.

Collector tarafında 76 test var. Faz 2'deki 73 teste ek olarak kaynak durumu yazımı ve İBB veri temizliği test ediliyor.

## Canlı deneme (compose)

`docker compose up --build` ile bütün stack'i (postgres, redis, collector, api, frontend) canlı kaynaklara karşı çalıştırdım. Bir SSE istemcisi baştan beri nginx üzerinden (`localhost:3000/api/stream`) bağlıydı.

- Beş container da healthy. Collector'ın ilk turu:

  | Kaynak | Yeni kayıt |
  |---|---|
  | BEDAŞ planlı | 250 |
  | AEDAŞ planlı | 231 |
  | ÇEDAŞ planlı | 135 |
  | KCETAŞ | 86 |
  | İSKİ | 18.380 |
  | BEDAŞ arıza | 3 |
  | AEDAŞ arıza | 2 |

- API stream'i sonuna kadar işledi: 19.087 olay, lag 0, bekleyen mesaj 0. Veritabanında 19.087 satır var.
- O an aktif kesintiler: BEDAŞ 18, AEDAŞ 12, ÇEDAŞ 20, KCETAŞ 2. İSKİ'de 39 aktif kayıt vardı, bu hataydı (bkz. "Nerede takıldım").
- `/api/sources`: altı kaynak da `stale: false`.
- `/api/map/summary`: 38 ilçe.
- `/api/outages?active=true` nginx üzerinden 91 kayıt döndü.
- Swagger UI, `/v3/api-docs` ve `/canli.html` 200 döndü.
- İlk yüklemede tarayıcıya 417 `outage.created` gitti (geçmiş veri kuralıyla, öncesinde 18 bin gidecekti). 9 heartbeat geldi.
- **Gecikme, sentetik olayla**: stream'e NEW yazıldıktan tarayıcıya ulaşana kadar nginx üzerinden 255 ms geçti, GONE ile `outage.ended` arası 249 ms. Bu süreye test script'inin 50 ms'lik yoklama aralığı da dahil. Sunucu tarafında (`api_sse_delivery`) 423 olayın ortalaması 1,5 ms.
- İkinci turda BEDAŞ'ta ve AEDAŞ'ta birer arıza giderildi. İkisi de GONE olarak geldi ve tarayıcıya `outage.ended` gitti. Lag ve bekleyen mesaj yine 0.
- Bellek: api 274 MiB / 512, collector 275 MiB / 384, postgres 69 MiB, redis 25 MiB, frontend 16 MiB.

**Düzeltmeden sonra tekrar (2026-09-14)**: İBB parser düzeltmesinden sonra aynı denemeyi boş veritabanıyla baştan çalıştırdım.
- İSKİ: 18.379 kayıt, aktif 0. İlk denemede 39'du.
- Harita özetinin üstünde artık su kesintisi yok, sadece elektrik (Zeytinburnu 7, Sarıyer 6, Alanya 6). Özette 60 ilçe var.
- O anki aktif kesintiler: BEDAŞ 66, AEDAŞ 48, KCETAŞ 18, ÇEDAŞ 1. nginx üzerinden `active=true` 133 kayıt döndü. Sayılar ilk denemeden farklı, çünkü kaynaklar bir gün sonra başka kesintiler listeliyor.
- api stream'i sonuna kadar işledi: 19.022 olay, lag 0, bekleyen mesaj yok. Altı kaynak da `stale: false`.
- İlk yüklemede tarayıcıya 349 `outage.created` gitti, 13 heartbeat geldi.
- Gecikme: NEW'den tarayıcıya 228 ms, GONE'dan `outage.ended`'a 229 ms (nginx üzerinden, 50 ms yoklama dahil). Sunucu tarafında 351 olayın ortalaması 1,4 ms.
- İkinci turda ÇEDAŞ'ta bir arıza giderildi (GONE), BEDAŞ, AEDAŞ ve ÇEDAŞ'ta birer yeni arıza geldi. Lag ve bekleyen mesaj yine 0.
- Bellek: api 308 MiB / 512, collector 271 MiB / 384, postgres 94 MiB, redis 30 MiB, frontend 17 MiB.

## Nerede takıldım

- **İkinci API örneği yanlış veritabanına bağlandı.** İki örnekli testte ikinci uygulama yerel veritabanına bağlanmaya çalıştı. `SpringApplicationBuilder.properties()` en düşük öncelikli varsayılanları ayarlıyor, `application.yml` onları eziyor. Özellikleri komut satırı argümanı olarak verince düzeldi.
- **Türkçe karakter anahtarı bozuldu.** Filtre testinde `"ŞİŞLİ".toLowerCase()` Türkçe olmayan locale'de `i`'nin arkasına ayrı bir birleşik nokta (U+0307) koyuyordu ve anahtar bozuluyordu. `Names.key` artık önce Türkçe kurallarıyla büyük harfe çeviriyor, sonra Unicode ayrıştırmasıyla (NFD) işaretleri atıyor.
- **Test istemcisi olay kaybetti.** Test SSE istemcisi beklemediği olayı atıyordu. Zaman taramasında olaylar farklı sırayla gelince ikincisi kayboldu. İstemci artık eşleşmeyen olayları saklıyor.
- **Geçmiş veri tarayıcıyı boğacaktı.** İlk canlı denemede İSKİ'nin geçmiş verisinden 18 bin kayıt tarayıcıya `outage.created` olarak gidecekti. Kaynaktan ilk geldiğinde zaten bitmiş kesinti artık veritabanına yazılıyor ama yayınlanmıyor. İlk yüklemede 18 bin yerine 417 olay gitti.
- **İSKİ'de sahte aktif kesintiler.** Aynı denemede İSKİ'den 39 kayıt "aktif" göründü, harita özetinin başında İstanbul'da 15-16 aktif su kesintisi vardı. Sebep 2022-2023 dosyasında bitişi boş 39 satırdı: bitişsiz kesinti sonsuza kadar sürüyor sayılıyordu. Ayrıca ilçe adları kısaltılmıştı (G.O.PAŞA, B.ÇEKMECE, K.ÇEKMECE) ve 2022-2023 dosyası 2023-2024'ten farklı bir şemadaydı. Bunlar Faz 2'nin normalizasyonunda gözümden kaçmıştı. İBB parser'ını düzelttim (ayrıntı: [02-collector.md](02-collector.md)).

## Sonradan: aktif kesinti indeksleri ve derin sayfalama (2026-09-17)

Faz 5 sonrası gözden geçirmede iki şey eklendi (`V2__active_indexes.sql`):

- **Kısmi indeksler.** Veritabanındaki 19 bin satırın 18 bini İSKİ'nin geçmiş verisi ve kaynaktan kalkmış kayıtlar (`gone_at` dolu) ne listede ne harita özetinde sayılıyor. İki indeks bu kayıtları dışarıda bırakıyor: `outage_active_starts_idx` (liste: `starts_at desc` sıralaması ve aktiflik filtresi), `outage_active_district_idx` (harita özeti: il/ilçe, tür, planlı/arıza). Faz 9'daki k6 yük testi bu sorguları zorlayacak.
- **Derin sayfalama reddediliyor.** `page * size` 50.000'i geçerse 400 dönüyor. Öncesinde `page=100000` gibi bir istek veritabanına koca bir offset taraması yaptırırdı; dışarı açık bir API'de bu ucuz bir yük bindirme yolu.

## Bilinen sınırlar

- `last_seen_at` "en son görüldüğü tarama" değil, "en son değiştiği tarama". Collector sadece değişenleri yazıyor, API değişmeyen kaydın hâlâ listede olduğunu bilmiyor. Plandaki "kaynaktan kaybolan ama bitiş saati gelmemiş kayıtlar last_seen_at ile takip edilir" cümlesini `gone_at` karşılıyor.
- Kaçırılan olaylar için en çok 1000 olay tekrar gönderiliyor ve son 10.000 olay tutuluyor. Daha uzun kopukluklarda `outage.resync` gidiyor.
- `api_sse_clients` pod başına. Toplam için Prometheus'ta toplanacak.
- Özet cache'i zamanla başlayan/biten kesintiler için TransitionSweeper'a güveniyor. O çalışmazsa 10 dakikalık TTL ile düzeliyor.
