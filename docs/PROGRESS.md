# İlerleme

Plan: [plan.md](plan.md). İngilizcesi: [en/PROGRESS.md](en/PROGRESS.md).

Fazların tam tanımı: [tr/fazlar.md](tr/fazlar.md). Bu dosyadaki maddeler özet, ayrıntının kaynağı orası.

Her faz için ortak koşul: testler ve build yeşil, commit atılmış, `docs/tr/NN-...md` ve `docs/en/NN-...md` yazılmış, bu dosya güncellenmiş. Faz bitince durulur, sonraki faza onaysız geçilmez.

## [x] Faz 1 - Keşif ve iskelet
- [x] BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ kesinti sayfaları incelendi (URL, format, alanlar, planlı/arıza ayrımı, zorluklar)
- [x] Her kaynaktan örnek yanıtlar fixture olarak kaydedildi
- [x] Monorepo iskeleti (services/collector, services/api, frontend, helm, gitops, infra, loadtest, docs, .github/workflows)
- [x] docker-compose.yml: postgres, redis, collector, api, frontend
- [x] README.md / README.en.md

Bitti sayılma koşulu: kaynak notları docs'ta, zor kaynaklar için karar önerisi yazılmış; fixture'lar `services/collector/src/test/resources/fixtures` altında; `docker compose up` ile beş container ayağa kalkıyor ve iki servisin health endpoint'i ile frontend cevap veriyor; iki servisin `mvn verify` çıktısı yeşil.

Durum: tamamlandı (2026-09-11). Not: [tr/01-kesif-ve-iskelet.md](tr/01-kesif-ve-iskelet.md).
Kararlar (2026-09-11): v1 kaynakları BEDAŞ (elektrik) ve İSKİ (su, İBB Açık Veri'deki "Su Kesintileri" dosyası). AYEDAŞ v1'de yok (reCAPTCHA). İSKİ'nin gömülü token'ı kullanılmıyor. Veri modeline nullable `external_id`, `lat`, `lon` eklendi ([tr/veri-modeli.md](tr/veri-modeli.md)). Doğalgaz için uygun kaynak bulunamadı (İGDAŞ robots.txt, İBB'de İGDAŞ kesinti verisi yok, Başkentgaz yayınlamıyor, İzmirgaz sadece sokak bazlı sorgu).
Sonraki kararlar (2026-09-11): İSKİ geçmiş verisi v2.1 mahalle karnesinde kullanılacak; İBB taraması günde bir; tarama sıklığı kuralı CLAUDE.md'de güncellendi; İSKİ, İGDAŞ, Başkentgaz talep taslakları `docs/tr/talepler/` altında.
Kaynak taraması (21 elektrik dağıtım şirketi, 10 su idaresi): [tr/01-kaynak-taramasi.md](tr/01-kaynak-taramasi.md). Onaylandı (2026-09-13): v1 elektrik BEDAŞ + AEDAŞ + ÇEDAŞ + KCETAŞ, v1 canlı su İZSU, v1.1'de doğalgaz yerine ASKİ, İBB xlsx fixture'ı repoda kalıyor.

## [ ] Faz 2 - Collector
- [ ] Ortak `Outage` modeli ve `SourceCollector` arayüzü
- [ ] BEDAŞ (planlı: `GetItemsData`, arıza: `RetrieveOutages` + trafo konum cache'i) ve İSKİ (İBB Açık Veri XLSX) collector'ları, fixture tabanlı testlerle
- [ ] Her istekten önce robots.txt kontrolü (yasaksa istek atılmaz, Crawl-delay'e uyulur)
- [ ] AEDAŞ ve ÇEDAŞ (BEDAŞ ile aynı CK Enerji altyapısı, aynı parser), KCETAŞ (tarih başına tek JSON isteği) ve İZSU (canlı su, sunucuda render edilen tablo) collector'ları
- [ ] Tarih/saat ve il/ilçe/mahalle normalizasyonu
- [ ] Kaynak başına zamanlama, kaynağın güncellenme sıklığına göre ve en sık 5 dk (arıza 5 dk, planlı 15 dk, İBB açık veri günde bir), config'ten değiştirilebilir
- [ ] İsteklerde jitter
- [ ] Hash ile değişiklik tespiti, Redis Stream'e NEW / UPDATED / GONE
- [ ] Prometheus metrikleri: `collector_last_success_timestamp`, `collector_items_total`, `collector_errors_total`
- [ ] Bir kaynağın hatası diğerlerini durdurmuyor

Bitti sayılma koşulu: her collector için fixture'dan parse testi, normalizasyon testleri, diff (NEW/UPDATED/GONE) testleri ve hata izolasyonu testi yeşil; lokal compose'da collector çalışınca stream'e olay düşüyor, ikinci taramada değişiklik yoksa hiçbir şey yazılmıyor; `/actuator/prometheus` üç metriği kaynak etiketiyle gösteriyor.

## [ ] Faz 3 - API
- [ ] Consumer group ile stream tüketimi, `dedup_key` ile upsert (`external_id` varsa `source:external_id`, yoksa hash), Flyway migration'ları
- [ ] `GET /api/outages`, `GET /api/outages/{id}`, `GET /api/map/summary` (Redis cache), `GET /api/sources`, `GET /api/stream` (SSE)
- [ ] `outage.created` / `outage.updated` / `outage.ended` olayları, ilçe özeti cache'te güncelleniyor
- [ ] Redis Pub/Sub ile pod'lar arası SSE dağıtımı
- [ ] Last-Event-ID ve heartbeat
- [ ] Ayrı liveness/readiness, `/actuator/prometheus`
- [ ] Testcontainers entegrasyon testleri

Bitti sayılma koşulu: Testcontainers (Postgres + Redis) ile stream'den gelen olayın veritabanına yazıldığı, tekrar gelen olayın çift kayıt üretmediği, SSE istemcisinin olayı aldığı ve iki API instance'ı arasında Pub/Sub dağıtımının çalıştığı testler yeşil; compose ile collector -> api -> SSE hattı elle doğrulanmış.

## [ ] Faz 4 - Frontend
- [ ] React + Vite + Leaflet, açık lisanslı il/ilçe GeoJSON (lisansı docs'ta)
- [ ] İlçe renklendirme, tür filtresi, ilçeye tıklayınca liste
- [ ] SSE ile canlı güncelleme ve vurgu animasyonu
- [ ] Veri tazeliği göstergesi, kaynak bazında son tarama, gecikme uyarısı
- [ ] Bağlantı durumu ikonu
- [ ] Sürüm/ortam etiketi, "Yenilikler" penceresi
- [ ] Mobil uyum

Bitti sayılma koşulu: `npm run build` ve bileşen testleri yeşil; compose ile açılan sayfada yeni kesinti sayfa yenilenmeden haritada vurgulanıyor; API kapatılınca "yeniden bağlanıyor" görünüyor; mobil genişlikte kullanılabilir.

## [ ] Faz 5 - Container ve CI
- [ ] Çok aşamalı Dockerfile'lar, non-root, küçük base image, frontend için nginx
- [ ] Servis başına GitHub Actions workflow'u (paths filtresi, cache, JaCoCo eşiği, SonarQube Cloud, Trivy, tag'de GHCR + Release)
- [ ] CHANGELOG.md
- [ ] YAPMAN GEREKEN: SonarQube Cloud, SONAR_TOKEN, GHCR paketlerini public yapma
- [ ] v1.0.0 tag komutları

Bitti sayılma koşulu: üç workflow da main üzerinde yeşil; Trivy CRITICAL'da kıran adım çalışıyor; tag atıldığında image GHCR'da ve Release oluşuyor (ilk tag kullanıcı tarafından atılır).

## [ ] Faz 6 - Altyapı
- [ ] Terraform: Hetzner CX23, firewall (22 sadece senin IP'n, 80/443 açık), SSH key, cloud-init ile k3s; `terraform.tfvars.example`
- [ ] Kubeconfig'i lokale alma adımları
- [ ] cert-manager + Let's Encrypt ClusterIssuer (staging, sonra prod)
- [ ] YAPMAN GEREKEN: Hetzner hesabı/token, alan adı, DNS A kaydı; terraform komutları

Bitti sayılma koşulu: `terraform validate` ve `terraform plan` temiz; kullanıcı apply ettikten sonra `kubectl get nodes` Ready, staging ve prod sertifikası test ingress'te alınmış.

## [ ] Faz 7 - Helm ve GitOps
- [ ] helm/collector, helm/api, helm/frontend (probe, limit, ConfigMap, secret referansı, TLS Ingress, api HPA)
- [ ] Hafif PostgreSQL ve Redis chart'ları, ortam başına ayrı veritabanı
- [ ] Argo CD, gitops/apps altında int ve prod Application'ları, gitops/int ve gitops/prod values
- [ ] CI'ın INT tag güncellemesi (branch kurallarıyla çakışmayan yöntem), PROD'a PR ile terfi
- [ ] int.<domain> ve <domain>

Bitti sayılma koşulu: `helm lint` ve `helm template` temiz; Argo CD'de int ve prod Synced/Healthy; v1.0 `https://<domain>` üzerinde canlı.

## [ ] Faz 8 - Gözlemlenebilirlik
- [ ] 4 GB'a sığacak kube-prometheus-stack değerleri
- [ ] ServiceMonitor'lar
- [ ] Grafana panelleri (JSON, provisioning): kaynak sağlığı, uygulama, cluster, SSE istemci sayısı, DB -> tarayıcı gecikmesi
- [ ] Telegram alarmı (arıza 30 dk, planlı 3 saat, günlük açık veri kaynağı 26 saat)
- [ ] Alarm testi için kaynak bozma yöntemi
- [ ] YAPMAN GEREKEN: Telegram bot token ve chat id

Bitti sayılma koşulu: paneller Grafana'da veriyle doluyor; kaynak bilerek bozulunca Telegram'a alarm düşüyor ve düzelince resolved geliyor; node bellek kullanımı makul seviyede.

## [ ] Faz 9 - v1.1 ve dayanıklılık
- [ ] Doğalgaz kaynağı: BEKLEMEDE. İGDAŞ robots.txt `Disallow: /`, İBB'de İGDAŞ kesinti verisi yok, Başkentgaz sitesinde kesinti yayınlamıyor, İzmirgaz sadece sokak bazlı sorgu veriyor (ayrıntı: [tr/01-kesif-ve-iskelet.md](tr/01-kesif-ve-iskelet.md)). Faz 9 başında yeniden bakılacak; kaynak bulunursa: collector, doğalgaz filtresi ve rengi, CHANGELOG, INT'e otomatik, PROD'a PR ile. Bulunamazsa v1.1'in yeni kaynağı ASKİ (Ankara canlı su arızaları, 2026-09-13 onaylandı); aynı hattan geçecek.
- [ ] k6 ani trafik senaryosu, HPA ve cache ölçümü, sonuçlar docs'ta
- [ ] Rollback denemesi
- [ ] Demo runbook'u (TR/EN)

Bitti sayılma koşulu: v1.1 (yeni kaynakla) PROD'da; k6 sonuçları (istek/sn, p95, hata oranı, pod sayısı) docs'ta; rollback ve geri alma adım adım denenmiş; runbook plan'daki demo senaryosunu komut komut kapsıyor.
