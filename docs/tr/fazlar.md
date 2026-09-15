# Fazlar

English: [../en/phases.md](../en/phases.md)

Bu dosya projenin 9 fazının tam tanımı. "Orijinal tanım" bölümü, proje başında verilen metnin kendisi. Kısaltılmadı, yeniden yazılmadı. Sonradan alınan kararlar orijinal metni değiştirmiyor, en alttaki "Kararlarla değişenler" bölümünde duruyor. İlerleme durumu: [../PROGRESS.md](../PROGRESS.md).

## Çalışma kuralları (orijinal)

Plan dosyasındaki mimariye, veri modeline ve repo yapısına uy; plandan sapman gerekirse nedenini söyle ve onay al.

Önce docs/PROGRESS.md dosyasını oluştur: aşağıdaki fazları checklist olarak yaz, her fazın altında "bitti sayılma koşulu"nu belirt. Sonra Faz 1'e başla. Her faz sonunda:
- testler ve build yeşil olmalı
- commit atılmış olmalı
- TR ve EN faz dokümanı yazılmış olmalı
- PROGRESS.md güncellenmiş olmalı
- bana kısa bir özet ver ve DUR, sonraki faza kendiliğinden geçme

## Orijinal tanım

### Faz 1 - Keşif ve iskelet
- BEDAŞ, AYEDAŞ, İSKİ ve İGDAŞ'ın kesinti sayfalarını incele (curl ile, kaynak başına birkaç istek). Her biri için: URL, veri formatı (HTML tablo, JSON endpoint, form arkası), alanlar, planlı/arıza ayrımı, zorluklar. Sonucu docs'a yaz. Bir kaynak çok zorsa söyle, v1'den çıkarmayı öner.
- Her kaynaktan örnek yanıtları fixture olarak kaydet.
- Monorepo iskeleti: services/collector, services/api, frontend, helm, gitops, infra, loadtest, docs, .github/workflows.
- docker-compose.yml: postgres, redis, iki servis, frontend. `docker compose up` ile her şey ayağa kalkmalı (servisler şimdilik boş health endpoint'i dönebilir).
- README (TR/EN): proje ne, nasıl çalıştırılır.

### Faz 2 - Collector
- Ortak Outage modeli ve SourceCollector arayüzü.
- BEDAŞ, AYEDAŞ, İSKİ collector'ları; her biri fixture tabanlı testlerle.
- Tarih/saat, il/ilçe/mahalle adlarını normalize et (Türkçe karakter, büyük/küçük harf, "MAH." gibi ekler).
- Kaynak başına iki ayrı zamanlama: arıza/anlık kesinti sayfası 5 dakikada, planlı kesinti sayfası 15 dakikada bir. Aralıklar config'ten kaynak bazında değiştirilebilir. Bir kurum ikisini tek sayfada veriyorsa o sayfa 5 dakikada taranır.
- İsteklere birkaç saniyelik rastgele gecikme (jitter) ekle, tüm kaynaklara aynı saniyede gidilmesin.
- Sadece değişenleri yayınla: her kaydın hash'ini tut, önceki taramaya göre yeni, değişmiş veya kaybolmuş kayıtları Redis Stream'e yaz (NEW / UPDATED / GONE). Değişiklik yoksa stream'e bir şey gitmez.
- Prometheus metrikleri: collector_last_success_timestamp{source}, collector_items_total{source}, collector_errors_total{source}.
- Bir kaynağın hata vermesi diğerlerini durdurmamalı.

### Faz 3 - API
- Stream tüketimi (consumer group), dedup_key ile upsert, Flyway migration'ları.
- Endpoint'ler: GET /api/outages (type, il, ilce, active filtreleri), GET /api/outages/{id}, GET /api/map/summary (Redis cache), GET /api/sources (her kaynağın son başarılı tarama zamanı), GET /api/stream (SSE).
- Kesinti eklendiği, değiştiği veya bittiği an SSE üzerinden olay yayınla (outage.created / outage.updated / outage.ended). Değişen ilçenin özeti cache'te de güncellensin.
- Birden fazla api pod'u çalışacağı için SSE olayları tek pod'a bağlı kalmasın: olaylar Redis Pub/Sub üzerinden tüm pod'lara dağıtılsın, her pod kendi bağlı istemcilerine iletsin.
- SSE bağlantısı koparsa istemci yeniden bağlanabilsin (Last-Event-ID desteği, periyodik heartbeat).
- Actuator health (liveness/readiness ayrı), /actuator/prometheus.
- Testcontainers ile entegrasyon testleri.

### Faz 4 - Frontend
- React + Vite + Leaflet. Türkiye il/ilçe sınırları için açık lisanslı bir GeoJSON bul, lisansını docs'a yaz, repoya ekle.
- İlçeleri aktif kesinti sayısına ve türüne göre renklendir; tür filtresi (elektrik/su/doğalgaz); ilçeye tıklayınca kesinti listesi (mahalleler, saatler, kaynak linki).
- SSE ile sayfa yenilemeden güncelleme; yeni düşen kesinti haritada kısa bir vurgu animasyonuyla belli olsun.
- Veri tazeliği göstergesi: "Son güncelleme: 2 dk önce" ve kaynak bazında son tarama zamanı (/api/sources). Bir kaynak gecikmişse kullanıcı bunu görsün.
- Canlı bağlantı durumu: bağlı / yeniden bağlanıyor ikonu.
- Köşede sürüm ve ortam etiketi (build argümanı/env'den), "Yenilikler" penceresi (CHANGELOG'dan).
- Mobilde düzgün görünmeli.

### Faz 5 - Container ve CI
- Her servis için çok aşamalı Dockerfile, non-root kullanıcı, küçük base image. Frontend için nginx.
- GitHub Actions: servis başına workflow, paths filtresi; build + test + Maven/npm cache; JaCoCo coverage eşiği; SonarQube Cloud analizi ve quality gate; Trivy image taraması (CRITICAL'da kırılsın); tag'de (collector-vX.Y.Z, api-vX.Y.Z, frontend-vX.Y.Z) GHCR'a push ve GitHub Release.
- CHANGELOG.md.
- YAPMAN GEREKEN kısmında: SonarQube Cloud kurulumu ve SONAR_TOKEN secret'ı, GHCR paketlerini public yapma.
- Faz sonunda v1.0.0 tag'lerini atmak için bana komutları ver.

### Faz 6 - Altyapı
- infra/terraform: Hetzner sunucu (CX23), firewall (22 sadece benim IP'm, 80/443 açık), SSH key, cloud-init ile k3s kurulumu. Değişkenler tfvars.example'da.
- Kubeconfig'i lokale alma adımları.
- cert-manager + Let's Encrypt ClusterIssuer (önce staging, sonra prod).
- YAPMAN GEREKEN: Hetzner hesabı ve API token, alan adı ve DNS A kaydı. Terraform'u benim çalıştırmam için komutları ver, sen apply etme.

### Faz 7 - Helm ve GitOps
- helm/collector, helm/api, helm/frontend: probe'lar, resource limitleri, ConfigMap, secret referansı, Ingress (TLS), api için HPA.
- PostgreSQL ve Redis için hafif Helm chart'lar; tek Postgres, ortam başına ayrı veritabanı.
- Argo CD kurulumu; gitops/apps altında int ve prod Application'ları; gitops/int ve gitops/prod values dosyaları.
- Akış: yeni image çıkınca CI, INT values'taki tag'i günceller (repodaki branch kurallarıyla nasıl çakışmayacağını çöz ve açıkla); PROD'a geçiş INT tag'ini PROD'a taşıyan PR ile.
- Adresler: int.<domain> ve <domain>.
- Faz sonunda v1.0 canlıda olmalı.

### Faz 8 - Gözlemlenebilirlik
- kube-prometheus-stack, 4 GB'a sığacak şekilde küçültülmüş değerlerle (gereksiz bileşenleri kapat, retention kısa).
- ServiceMonitor'lar.
- Grafana panelleri (JSON olarak repoda, provisioning ile yüklenir): kaynak sağlığı, uygulama (istek, gecikme, hata, cache hit), cluster (pod, CPU/bellek, HPA).
- Alarm: arıza sayfalarından 30 dakika, planlı kesinti sayfalarından 3 saat boyunca başarılı tarama yoksa Telegram'a bildirim.
- Panelde SSE bağlı istemci sayısı ve olayın veritabanından tarayıcıya ulaşma süresi de görünsün. YAPMAN GEREKEN: Telegram bot token ve chat id.
- Alarmı test etmek için bir kaynağı geçici olarak bozma yöntemi.

### Faz 9 - v1.1 ve dayanıklılık
- İGDAŞ collector'ı (doğalgaz); frontend'de doğalgaz filtresi ve rengi; CHANGELOG; tüm hattan geçir: INT'e otomatik, PR ile PROD'a.
- loadtest/: k6 ile ani trafik senaryosu (normal yük, sonra 10 kat sıçrama). HPA'nın ölçeklenmesini ve cache etkisini ölç, sonuçları docs'a yaz.
- Rollback denemesi: PROD'u bir önceki sürüme al, geri getir.
- Demo runbook'u (TR/EN): plan dosyasındaki demo senaryosunu komut komut yaz.

## Kararlarla değişenler

Orijinal metin yukarıda değiştirilmeden duruyor. Bu bölüm, faz tanımına dokunan kararları gösteriyor. Ayrıntılar faz notlarında.

| Tarih | Faz | Değişiklik | Durum | Nerede |
|---|---|---|---|---|
| 2026-09-11 | Faz 1, 2 | AYEDAŞ v1'den çıktı: veri reCAPTCHA'lı formun arkasında | karar | [01-kesif-ve-iskelet.md](01-kesif-ve-iskelet.md) |
| 2026-09-11 | Faz 1, 2 | İSKİ'nin sitesine gömülü token kullanılmıyor. İSKİ verisi İBB Açık Veri'deki "Su Kesintileri" dosyasından (geçmiş veri, en yenisi 2024-02-19) | karar | [01-kesif-ve-iskelet.md](01-kesif-ve-iskelet.md) |
| 2026-09-11 | Faz 2 | İBB veri seti günde bir taranıyor. Genel kural: tarama sıklığı kaynağın güncellenme sıklığına göre, en sık 5 dakika (CLAUDE.md güncellendi) | karar | CLAUDE.md, [../plan.md](../plan.md) |
| 2026-09-11 | Faz 2 | Her istekten önce robots.txt kontrolü. Captcha, WAF ya da robots.txt ile engelleyen kaynaklar kara listede | karar | [01-kaynak-taramasi.md](01-kaynak-taramasi.md) |
| 2026-09-11 | Faz 2 | v1 kaynaklarına AEDAŞ, ÇEDAŞ, KCETAŞ (elektrik) ve İZSU (canlı su) eklenmesi | karar (2026-09-13 onaylandı) | [01-kaynak-taramasi.md](01-kaynak-taramasi.md) |
| 2026-09-11 | Faz 3 | Veri modeline nullable `external_id`, `lat`, `lon`. Tekilleştirme: `external_id` varsa `(source, external_id)`, yoksa hash | karar | [veri-modeli.md](veri-modeli.md) |
| 2026-09-11 | Faz 8 | Günde bir taranan açık veri kaynağı için alarm eşiği 26 saat (arıza 30 dk ve planlı 3 saat aynen duruyor) | öneri, plan'a yazıldı | [../plan.md](../plan.md) |
| 2026-09-11 | Faz 9 | İGDAŞ taranamıyor (robots.txt `Disallow: /`). Başkentgaz kesinti yayınlamıyor, İzmirgaz sadece sokak bazlı sorgu veriyor. Doğalgaz maddesi beklemede. İGDAŞ ve Başkentgaz'a talep taslakları hazır | karar | [talepler/](talepler/) |
| 2026-09-11 | Faz 9 | v1.1'de doğalgaz yerine ASKİ (Ankara canlı su arızaları), yedekler BUSKİ ve MESKİ | karar (2026-09-13 onaylandı) | [01-kaynak-taramasi.md](01-kaynak-taramasi.md) |
| 2026-09-11 | v2.1 | İSKİ geçmiş verisi mahalle karnesinde kullanılacak | karar | [../plan.md](../plan.md) |
| 2026-09-13 | Faz 2 | İBB su kesintisi dosyası (`iski/ibb-su-kesintileri-2023-2024.xlsx`) fixture olarak repoda kalıyor | karar | [01-kaynak-taramasi.md](01-kaynak-taramasi.md) |
| 2026-09-13 | Faz 2, 8 | Collector metriklerine `source`'a ek olarak `feed` etiketi (arıza 30 dk / planlı 3 saat alarmını ayırmak için) | karar (2026-09-13 onaylandı) | [02-collector.md](02-collector.md) |
| 2026-09-13 | Faz 3 | API'nin tarayıcıdan kullanılabilecek arayüzü olsun: Swagger UI (REST) ve canlı olayları gösteren küçük bir SSE sayfası. Harita arayüzü Faz 4'te kalıyor | karar (kullanıcı isteği) | [03-api.md](03-api.md) |
| 2026-09-14 | Faz 4 | İlçe sınırları OCHA HDX COD-AB'den (HGK verisi, CC BY-IGO), GeoJSON yerine sadeleştirilmiş TopoJSON olarak. İl ve ilçe adlarının Türkçe yazımı Wikidata'dan (CC0) | karar | [04-frontend.md](04-frontend.md) |
| 2026-09-14 | Faz 4, 5 | CHANGELOG.md (ve CHANGELOG.en.md) "Yenilikler" penceresi için Faz 5 yerine Faz 4'te başladı. Frontend imajının build context'i repo kökü oldu | karar | [04-frontend.md](04-frontend.md) |
| 2026-09-15 | Faz 5 | Trivy sadece düzeltmesi yayınlanmış CRITICAL açıklarda kırıyor (`ignore-unfixed`) | karar | [05-container-ve-ci.md](05-container-ve-ci.md) |
| 2026-09-15 | Faz 5, 7 | Ortam etiketi (LOCAL/INT/PROD) build argümanı değil, çalışma anında `APP_ENV` (nginx `/env.json`): aynı imaj INT'ten PROD'a taşınabilsin diye | karar | [05-container-ve-ci.md](05-container-ve-ci.md) |
| 2026-09-15 | Faz 5 | İmaj her çalışmada derlenip taranıyor ama GHCR'a sadece tag'de gidiyor (`X.Y.Z` ve `latest`). `SONAR_TOKEN` yoksa Sonar adımı uyarıyla atlanıyor | karar | [05-container-ve-ci.md](05-container-ve-ci.md) |
| 2026-09-15 | Faz 5 | Servis başına üç workflow'a ek olarak dördüncüsü: `compose-smoke`, bütün stack'i compose ile kurup nginx üzerinden uçtan uca deniyor | karar (kullanıcı onayı) | [05-container-ve-ci.md](05-container-ve-ci.md) |

Bekleyen onaylar netleşince bu tabloya yeni satır eklenir. Orijinal tanım değiştirilmez.
