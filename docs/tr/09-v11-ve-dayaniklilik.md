# Faz 9 - v1.1 ve dayanıklılık

Planda bu fazın iki işi var: v1.1 için yeni bir kaynak (öncelik doğalgaz, bulunamazsa ASKİ) ve dayanıklılık işleri (k6 ile ani trafik senaryosu, HPA ölçümü, geri alma denemesi, demo runbook'u).

Kaynak tarafı bu sefer de kapandı; aşağıda hangi kapıları çaldığım ve neden her birinin elendiği yazıyor. Dayanıklılık işlerinin hepsi yapıldı ve ölçüldü.

## Yeni kaynak: tekrar bakıldı, yine yok

Faz 1'de doğalgaz kaynağı bulunamamıştı. Bu fazın başında hepsini yeniden yokladım (2026-10-02).

| Aday | Durum | Neden elendi |
|---|---|---|
| İGDAŞ (doğalgaz, İstanbul) | `robots.txt`: `User-agent: *` / `Disallow: /` | Site tarama izni vermiyor. Faz 1'deki durumun aynısı |
| ASKİ (su, Ankara) | Sayfa var: `Kesinti.aspx`, başlığı "Ankara Su Arızaları" | Liste GET cevabında yok; ASP.NET WebForms formu, il/ilçe/mahalle seçip VIEWSTATE ile POST etmek gerekiyor. Tarama başına onlarca POST demek; AYEDAŞ ve İzmirgaz'ı eleyen gerekçenin aynısı |
| Başkent EDAŞ (elektrik, Ankara) | Kesinti sayfası reCAPTCHA arkasında | Captcha. Üstelik CK Enerji ailesinden olduğu için parser'ımız hazırdı: `GetItemsData` 301 ile 404'e gidiyor, `kesintiapi.ckenerji.com.tr/<kod>/RetrieveOutages` denediğim kodlarda 404 |
| UEDAŞ (elektrik, Bursa) | `robots.txt` izinli, tablo başlıkları tam istediğimiz gibi (tarih, saat, neden, il, ilçe, mahalle, sokak) | Satırlar HTML'de yok: `/planli-kesintiler/sec.asp`'ye il/ilçe/mahalle seçimiyle POST atılıp XML alınıyor. Ayrıca yalnızca 48 saatlik planlı kesinti |
| KCETAŞ arıza | Sitede "arıza bildirimi" var | Bildirim formu, liste değil. Planlı kesintileri zaten topluyoruz |
| Ankara Büyükşehir açık veri | `data.ankara.bel.tr`, `acikveri.ankara.bel.tr` | Host çözülmüyor; DoH ile kontrol ettim, A kaydı yok (DNS sorunumuz değil) |
| BUSKİ (su, Bursa) | Ad çözülüyor | Bağlantı kurulamıyor (TLS/timeout), sayfa alınamadı |
| ADM, MEDAŞ | Denediğim host adları yok | A kaydı yok |

Sonuç: harita altı kaynakta kalıyor (BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ, İZSU ve İBB Açık Veri üzerinden İSKİ). Elenme sebepleri teknik değil, hepsi aynı yerde buluşuyor: ya site tarama izni vermiyor (robots, captcha) ya da veri ancak form doldurup sorgulayarak geliyor; ikincisi tarama başına onlarca isteğe çıkıyor ve CLAUDE.md'deki nezaket kuralına aykırı.

Bu yüzden v1.1 yeni kaynakla değil, dayanıklılık ve işletme tarafıyla çıktı.

## Ani trafik senaryosu (k6)

`loadtest/spike.js`: normal yük, 10 kat sıçrama, sonra düşüş. İki uç ayrı ölçülüyor, çünkü biri cache'li diğeri değil:

- `/api/map/summary`: haritanın açılışta istediği uç, 10 dakika Redis cache'li.
- `/api/outages?il=...&ilce=...`: ilçeye tıklamayı taklit ediyor, veritabanına gidiyor.
- `/api/sources`: kaynak durumu kutusu, daha seyrek.

`scripts/loadtest.sh` (yani `make loadtest`) k6'yı koşarken 10 saniyede bir HPA'yı örnekliyor ve replika sayısının nerede değiştiğini yazıyor.

Ölçüm (2026-10-02, PROD, lokal küme, 2 düğüm):

| Ne | Değer |
|---|---|
| Süre | 5 dakika (1 dk normal, 30 sn sıçrama, 2 dk tepede, 30 sn iniş, 1 dk normal) |
| Sanal kullanıcı | 10 → 100 → 10 |
| İstek | 16.661 (55,3 istek/sn), 10.816 tur |
| Hata | 0 (`http_req_failed` %0,00) |
| Özet ucu | ortalama 4,26 ms, p95 6,73 ms, en kötü 109 ms |
| İlçe listesi ucu | ortalama 4,48 ms, p95 7,15 ms, en kötü 57 ms |
| Tüm istekler | p95 6,75 ms |
| İndirilen | 128 MB (424 kB/sn) |

HPA'nın davranışı (10 saniyede bir örneklendi):

```
07:47:04  replika=1 hedef=1 cpu=3%     <- normal yuk
07:48:37  replika=1 hedef=3 cpu=157%   <- sicrama, HPA karar verdi
07:48:58  replika=3 hedef=3 cpu=196%   <- 21 saniyede 3 replika
```

Tepe CPU, istenen kaynağın (150m) %196'sı; ortalama %72. Test bittikten sonra CPU %12-16'ya indi ve HPA 300 saniyelik `stabilizationWindowSeconds` dolunca tek replikaya geri döndü. Üç pod da hazır duruma geçti, k6 hiç hata görmedi.

Çıkarımlar:

- **Cache çalışıyor.** Haritanın açılışta istediği özet ucu 100 sanal kullanıcı altında p95 6,73 ms veriyor; Redis'teki 10 dakikalık cache sayesinde yük veritabanına inmiyor.
- **İndeksler çalışıyor.** İlçe listesi (veritabanına giden uç) p95 7,15 ms. Faz 5 sonrası eklenen kısmi indeksler olmasa 19 bin satırda tablo taraması olurdu.
- **Darboğaz uygulama değil, CPU.** p95 7 ms'de kalırken CPU %196'ya çıktı, yani kısıt istenen CPU payı. 2 vCPU'luk bir sunucuda üç replika sığıyor ama HPA'nın üst sınırı (3) bu makinede makul; daha fazlası için düğüm büyütmek gerekir.
- **Ölçeklenme hızlı.** Sıçramadan 90 saniye sonra karar, karardan 21 saniye sonra üç replika. Bu süre boyunca hata olmadı, yani tek replika da yükü kuyruklayarak taşıdı.

## Geri alma denemesi

Geri alma da ileri alma gibi bir commit: `gitops/prod/api.yaml` içindeki imaj etiketi değişiyor, Argo CD görüp uyguluyor. Kümeye elle dokunulmuyor ve ne zaman ne olduğu git'te duruyor.

1.0.0 ile 1.0.1 arasında gözle doğrulanabilir bir fark var: derin sayfa isteği (`page=2000&size=100`) 1.0.1'de 400 dönüyor, 1.0.0'da (sertleştirme öncesi kod) 200.

Ölçüm (2026-10-02):

| Adım | Süre | Davranış kontrolü |
|---|---|---|
| 1.0.1 → 1.0.0 (geri alma) | push'tan 30 saniye sonra bütün pod'lar 1.0.0 ve rollout tamam | derin sayfa 200 (beklenen) |
| 1.0.0 → 1.0.1 (ileri alma) | 34 saniye | derin sayfa 400 (beklenen) |

İlk denemede yanlış ölçtüm: "hedef sürüm hazır" kontrolünü `readyReplicas >= 1` ile yapmıştım, bu rollout'un ortasında da doğru oluyor ve eski pod'lar cevap vermeye devam ettiği için davranış eski sürümde de 400 görünüyordu. Doğru kontrol, deployment'ın bütün pod'larının hedef imajda olması ve `rollout status`'un tamamlanması.

## Harita eşleşmesi: 174 kesinti görünmüyordu

Fazın sonunda uygulamayı elle gezerken özet uçta "ANTALYA / KONYAALTI / KEPEZ" ve "İSTANBUL / ZİNCİRLİKUYU" gibi ilçe adları gördüm. Zincirlikuyu bir ilçe değil, semt. Bu adlar sınır verisindeki poligonlarla eşleşmiyorsa o kesintiler haritada hiç renklenmiyor demek.

Ölçtüm: veritabanındaki 19.285 kaydın **174'ü (%0,9)**, 14 grupta, hiçbir poligona düşmüyordu. Frontend `ilKey|ilceKey` kimliğiyle eşliyor (`lib/districts.js`) ve zaten yalın "MERKEZ"i ilin adına çeviriyor; geri kalan dört sebep açıkta kalmış:

| Sebep | Örnek | Kayıt |
|---|---|---|
| Birleşik ilçe | AEDAŞ: "KONYAALTI / KEPEZ" | 46 |
| İlçe yerine semt | BEDAŞ: Yenibosna, Zincirlikuyu, Kumburgaz, Beyazıt, Çağlayan, Kilyos, Kemerburgaz, Hadımköy | 110 |
| İl adı öneki | ÇEDAŞ: "SİVAS (MERKEZ)", "TOKAT MERKEZ" | 16 |
| İl adı önekli ilçe | AEDAŞ: "BURDUR KEMER", ÇEDAŞ: "SİVAS KIRSAL" | 2 |

Düzeltme tek yerde: `normalize/Districts.java`, taramadan sonra diff'ten önce `ScanRunner` içinde bütün kaynaklara uygulanıyor. Kurallar sırayla parantezli eki atmak, il adı önekini atmak, "MERKEZ"/"KIRSAL"ı ilin adına çevirmek, takma ad tablosundan semti ilçeye çevirmek ve ayraçla yazılmış ilçeleri ayrı kayıtlara bölmek.

İki ayrıntı önemliydi:

- **Bölünen kayıtların dedup anahtarı.** Anahtar `source:external_id` olduğu için aynı id'yi iki kayda verseydim ikincisi birincinin üzerine yazardı; bölünen kayıtlarda id'ye ilçe ekleniyor (`42#KONYAALTI`).
- **Bilgi kaybetmemek.** Semt ilçeye çevrilirken kaynağın yazdığı semt adı mahalle listesinin başına ekleniyor, yoksa "Yenibosna'daki kesinti" bilgisi kayboluyordu.

Düzeltme PROD'a çıktıktan sonra rapor 139'dan **1**'e indi ve kalan tek kayıt kuralın sınırını gösterdi: AEDAŞ o kaydı `BURDUR_KEMER` diye yazmış, yani il önekini alt çizgiyle bağlamış. Kuralım yalnızca boşlukla ayrılmış öneki atıyordu. 1.0.3'te alt çizgi de ayraç sayıldı ve rapor sıfıra indi. (CK Enerji'nin arıza API'si bu biçimi `CkCompany.resolve()` ile zaten çözüyordu; planlı kesinti verisinde aynı biçim normalleştirilmemiş geliyor.)

Takma ad tablosu elle tutuluyor, bu yüzden raporu da script'e çevirdim: `make map-match` veritabanındaki adları sınır dosyasıyla karşılaştırıp eşleşmeyenleri kaynağıyla listeliyor ve varsa 1 ile çıkıyor. Yeni bir kaynak eklenince ya da bir kaynak ad biçimini değiştirince bu rapor söyleyecek.

## Demo runbook

[demo-runbook.md](demo-runbook.md): 15-20 dakikalık demo akışı, komut komut. Hazırlık listesi, uygulamanın gösterilmesi, iki ortam, GitOps (kümede elle yapılan değişikliğin geri alınması dahil), sürüm hattı, Grafana panoları, alarm denemesi, yük testi, geri alma ve kapanış kontrol listesi. Sık sorulan sorular bölümünde sertifika uyarısı, `*.localhost` adresleri ve INT'in neden boş olduğu var.

## Takıldığım yerler

- **`yq` bu makinede yok.** Terfi workflow'ları `yq` kullanıyor çünkü GitHub runner imajında kurulu geliyor; lokalde kurulu değil. Geri alma denemesinde etiketi python ile değiştirdim.
- **Kirli çalışma dizininde `git pull --rebase` çalışmıyor.** Denemenin ortasında yazılmakta olan doküman dosyaları vardı; `git -c rebase.autoStash=true pull --rebase` ile çözdüm.
- **Geri alma denemesinin ilk ölçümü yanlıştı** (yukarıda): rollout ortasında eski pod'lar hâlâ cevap veriyor.
- **Ölü host adları.** Kaynak araştırmasında birkaç adres bağlantı kurmadı. DNS'imizin bozulup bozulmadığını DoH ile (`https://1.1.1.1/dns-query`) kontrol ettim: `data.ankara.bel.tr` ve denediğim ADM/MEDAŞ adlarının A kaydı yok, yani adresler gerçekten yanlış. Bu ayrım olmadan "DNS yine bozuldu" diye yanlış teşhis koyabilirdim.
- **Alarm testinden sonra düzelme hemen olmuyor.** NetworkPolicy 07:58:42'de kalktı, ilk başarılı taramalar 08:01:50'de geldi: aradaki fark tarama aralığı (5 dakika), robots cache'i değil. Faz 5'teki "robots alınamazsa geçerli kopyayı kullan" düzeltmesi sayesinde robots tarafı sorun çıkarmadı.
- **k6 kurulumu.** Depoda paket yok, binary'yi GitHub release'inden aldım ve resmi checksum listesiyle doğruladım (`~/.local/bin/k6`, sudo gerekmiyor).
- **Terfi hattında sıralama hatası.** 1.0.2'yi çıkarırken gördüm: `promote-int` tag atıldığı anda tetikleniyor ve servis workflow'uyla **aynı anda** çalışıyor. Values dosyası imaj derlenmeden güncellendiği için Argo CD henüz GHCR'da olmayan etiketi aramaya başladı ve yeni pod `ErrImagePull` verdi. Kesinti olmadı (Kubernetes eski pod'u ayakta tutuyor, rollout bekliyor) ama rollout imaj gelene kadar takıldı. `promote-int` artık values'i güncellemeden önce imajın GHCR'da görünmesini bekliyor (anonim manifest sorgusu, en fazla 20 dakika); gelmezse hata veriyor.
- **k3d düğümleri yeniden başlatmada IP takas etti.** `wsl --shutdown` sonrası server düğümü .3'ten .4'e geçti ama kubelet'in sunucu sertifikası eski IP'ye yazılıydı; `kubectl exec` ve `logs` o düğümdeki pod'larda TLS hatası verdi (`certificate is valid for 172.19.0.3, not 172.19.0.4`). Çözüm: container içindeki `serving-kubelet.crt`/`.key` silinip düğüm yeniden başlatılıyor, k3s sertifikayı güncel IP ile yeniden üretiyor. Bu ölçümümü de bozmuştu: psql sorgusu sessizce boş dönüyordu.
