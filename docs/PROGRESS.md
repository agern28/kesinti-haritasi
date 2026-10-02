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

## [x] Faz 2 - Collector
- [x] Ortak `Outage` modeli ve `SourceCollector` arayüzü
- [x] BEDAŞ (planlı: `GetItemsData`, arıza: `RetrieveOutages` + trafo konum cache'i) ve İSKİ (İBB Açık Veri XLSX) collector'ları, fixture tabanlı testlerle
- [x] Her istekten önce robots.txt kontrolü (yasaksa istek atılmaz, Crawl-delay'e uyulur)
- [x] AEDAŞ ve ÇEDAŞ (BEDAŞ ile aynı CK Enerji altyapısı, aynı parser), KCETAŞ (tarih başına tek JSON isteği) ve İZSU (canlı su, sunucuda render edilen tablo) collector'ları
- [x] Tarih/saat ve il/ilçe/mahalle normalizasyonu
- [x] Kaynak başına zamanlama, kaynağın güncellenme sıklığına göre ve en sık 5 dk (arıza 5 dk, planlı 15 dk, İBB açık veri günde bir), config'ten değiştirilebilir
- [x] İsteklerde jitter
- [x] Hash ile değişiklik tespiti, Redis Stream'e NEW / UPDATED / GONE
- [x] Prometheus metrikleri: `collector_last_success_timestamp`, `collector_items_total`, `collector_errors_total`
- [x] Bir kaynağın hatası diğerlerini durdurmuyor

Bitti sayılma koşulu: her collector için fixture'dan parse testi, normalizasyon testleri, diff (NEW/UPDATED/GONE) testleri ve hata izolasyonu testi yeşil; lokal compose'da collector çalışınca stream'e olay düşüyor, ikinci taramada değişiklik yoksa hiçbir şey yazılmıyor; `/actuator/prometheus` üç metriği kaynak etiketiyle gösteriyor.

Durum: tamamlandı (2026-09-13). Not: [tr/02-collector.md](tr/02-collector.md). 73 test yeşil. Compose'da canlı kaynaklarla iki tur: ilk turda 19.085 olay (18.380'i İSKİ geçmiş verisi), ikinci turda değişmeyen feed'ler stream'e hiçbir şey yazmadı.
Onaylandı (2026-09-13): metriklerde `source`'a ek olarak `feed` etiketi (Faz 8'de arıza 30 dk / planlı 3 saat alarmını ayırmak için).

## [x] Faz 3 - API
- [x] Consumer group ile stream tüketimi, `dedup_key` ile upsert (`external_id` varsa `source:external_id`, yoksa hash), Flyway migration'ları
- [x] `GET /api/outages`, `GET /api/outages/{id}`, `GET /api/map/summary` (Redis cache), `GET /api/sources`, `GET /api/stream` (SSE)
- [x] `outage.created` / `outage.updated` / `outage.ended` olayları, ilçe özeti cache'te güncelleniyor
- [x] Redis Pub/Sub ile pod'lar arası SSE dağıtımı
- [x] Last-Event-ID ve heartbeat
- [x] Ayrı liveness/readiness, `/actuator/prometheus`
- [x] Testcontainers entegrasyon testleri
- [x] Tarayıcıdan kullanılabilen arayüz: Swagger UI ve canlı SSE olay sayfası (kullanıcı isteği)

Bitti sayılma koşulu: Testcontainers (Postgres + Redis) ile stream'den gelen olayın veritabanına yazıldığı, tekrar gelen olayın çift kayıt üretmediği, SSE istemcisinin olayı aldığı ve iki API instance'ı arasında Pub/Sub dağıtımının çalıştığı testler yeşil; compose ile collector -> api -> SSE hattı elle doğrulanmış.

Durum: tamamlandı (2026-09-14). Not: [tr/03-api.md](tr/03-api.md). api'de 24, collector'da 76 test yeşil. Compose'da canlı kaynaklarla collector -> api -> SSE hattı nginx üzerinden doğrulandı: 19.022 olay işlendi, lag 0, yeni olay tarayıcıya yaklaşık 230 ms'de ulaştı. İBB veri temizliğinden sonra İSKİ'den sahte aktif kayıt kalmadı.

## [x] Faz 4 - Frontend
- [x] React + Vite + Leaflet, açık lisanslı il/ilçe GeoJSON (lisansı docs'ta)
- [x] İlçe renklendirme, tür filtresi, ilçeye tıklayınca liste
- [x] SSE ile canlı güncelleme ve vurgu animasyonu
- [x] Veri tazeliği göstergesi, kaynak bazında son tarama, gecikme uyarısı
- [x] Bağlantı durumu ikonu
- [x] Sürüm/ortam etiketi, "Yenilikler" penceresi
- [x] Mobil uyum

Bitti sayılma koşulu: `npm run build` ve bileşen testleri yeşil; compose ile açılan sayfada yeni kesinti sayfa yenilenmeden haritada vurgulanıyor; API kapatılınca "yeniden bağlanıyor" görünüyor; mobil genişlikte kullanılabilir.

Durum: tamamlandı (2026-09-14). Not: [tr/04-frontend.md](tr/04-frontend.md). 34 frontend testi ve build yeşil. Compose'da canlı kaynaklarla, headless Chromium ile denendi: yeni kesinti sayfa yenilenmeden yarım saniyede haritada vurgulandı, api kapatılınca "Yeniden bağlanıyor" göründü, 390 px genişlikte yatay kaydırma yok. İlçe sınırları OCHA HDX COD-AB (HGK verisi, CC BY-IGO), TopoJSON olarak. Bu fazda collector'da KCETAŞ'ın il hatası da düzeltildi (Gemerek Sivas'ta), collector'da 78 test yeşil. CHANGELOG.md Yenilikler penceresi için bu fazda başladı.

## [x] Faz 5 - Container ve CI
- [x] Çok aşamalı Dockerfile'lar, non-root, küçük base image, frontend için nginx
- [x] Servis başına GitHub Actions workflow'u (paths filtresi, cache, JaCoCo eşiği, SonarQube Cloud, Trivy, tag'de GHCR + Release)
- [x] CHANGELOG.md
- [x] YAPMAN GEREKEN: SonarQube Cloud, SONAR_TOKEN, GHCR paketlerini public yapma
- [x] v1.0.0 tag komutları

Bitti sayılma koşulu: üç workflow da main üzerinde yeşil; Trivy CRITICAL'da kıran adım çalışıyor; tag atıldığında image GHCR'da ve Release oluşuyor (ilk tag kullanıcı tarafından atılır).

Durum: tamamlandı (2026-09-15). Not: [tr/05-container-ve-ci.md](tr/05-container-ve-ci.md). Yerelde hepsi yeşil: iki serviste `mvn verify` ve JaCoCo alt sınırı (%85; ölçülen %91 ve %92), frontend testleri ve kapsam alt sınırı, üç imaj, `actionlint`. Trivy ilk yerel taramada Tomcat 11.0.24'teki üç CRITICAL açıkta kırdı, 11.0.25'e sabitlenince temiz. GitHub'da (2026-09-15, `main`): collector, api ve frontend workflow'ları yeşil (test, kapsam alt sınırı, imaj, Trivy); `compose-smoke` ilk koşuda `--ip` yüzünden kırıldı, düzeltmeden sonra yeşil. SonarQube Cloud kuruldu, `SONAR_TOKEN` eklendi: ilk analizde gate hesaplanmadığı için kırmızı, ikinci analizden itibaren üç projede de `OK`. İlk analizin 7 bulgusu (2'si yanlış alarm olan dinamik SQL, 5 küçük bug) düzeltildi. v1.0.0: ilk tag'ler Sonar adımında kırıldı (SonarCloud tag'i ayrı bir dal sayıyor); tag koşularında Sonar atlanınca tag'ler `11f3377`'ye taşındı. Üç tag koşusu yeşil, imajlar GHCR'da (`1.0.0`, `latest`, public), üç GitHub Release notunu CHANGELOG'dan aldı ve SBOM'u ekli.

## [x] Faz 6 - Altyapı (lokalde)
- [x] Lokal k3s kümesi: `infra/k3d/cluster.yaml` (1 server + 1 agent, k3s v1.35.5 sabit, 80/443 localhost'a bağlı, metrics-server açık)
- [x] Terraform (`infra/terraform/local`): `kesinti-int` ve `kesinti-prod` namespace'leri, cert-manager v1.21.2, issuer chart'ı; `terraform.tfvars.example`
- [x] cert-manager + kendi CA'mızdan ClusterIssuer (`helm/cluster-issuers`, `letsencrypt.enabled` ile ACME moduna geçiyor)
- [x] Make komutları: `cluster-up`, `bootstrap`, `cluster-status`, `cluster-down`
- [x] Beşinci workflow: `infra.yml` (terraform fmt/validate, helm lint, issuer chart'ını iki modda render, `cluster.yaml` YAML kontrolü)
- [ ] Hetzner yolu (hcloud provider, CX23, firewall, cloud-init, Let's Encrypt): ertelendi, gereken iş [tr/06-altyapi.md](tr/06-altyapi.md) sonunda
- Senden bir şey gerekmiyor: hesap, token, alan adı ve DNS yok

Durum: tamamlandı (2026-10-02). Not: [tr/06-altyapi.md](tr/06-altyapi.md). `terraform validate` ve `terraform fmt` temiz, plan 4 kaynak, apply 40 saniyede bitti. `kubectl get nodes`: iki düğüm Ready. `kubectl get clusterissuers`: `selfsigned-bootstrap` ve `kesinti-ca` True, mesaj "Signing CA verified". Test Certificate'ı (`int.kesinti.localhost`) iki saniyede hazır oldu, `issuer=CN=Kesinti Haritasi Lokal CA`, 90 gün geçerli; sonra silindi (gerçek Ingress sertifikaları Faz 7'de). Traefik 80 ve 443'te cevap veriyor (Ingress olmadığı için 404). Windows `kesinti.localhost`'u kendisi ::1'e çözüyor. Küme + compose yığını 2,3 GB RAM. Takıldığım yerler: `sudo` şifre istediği için k3d `~/.local/bin`'e kuruldu; k3d v5.9.0'ın ayrı sha256 dosyası yok; cert-manager varsayılanı v1.19.2'den güncel v1.21.2'ye çekildi; `kubernetes_manifest` CRD'yi plan aşamasında aradığı için issuer'lar Helm chart'ına taşındı.

## [x] Faz 7 - Helm ve GitOps
- [x] helm/collector, helm/api, helm/frontend: probe'lar servisin health gruplarına bağlı, resource limitleri, secret referansı (üretilen DB parolası), TLS Ingress (sadece frontend'de), api'de HPA
- [x] helm/data: tek PostgreSQL ve tek Redis, ortam başına ayrı veritabanı (`kesinti_int`, `kesinti_prod`) ve ayrı Redis logical DB
- [x] Argo CD (Terraform ile), `gitops/apps` altında 7 Application + kök Application, `gitops/int` ve `gitops/prod` values
- [x] `promote-int` (servis tag'i → INT values → Argo CD) ve `promote-prod` (elle tetiklenen PR ile terfi)
- [x] Adresler: https://int.kesinti.localhost ve https://kesinti.localhost, Argo CD https://argocd.localhost
- [x] 1.0.1 çıkarıldı ve hat uçtan uca çalıştırıldı: tag → imaj + Release → `promote-int` → Argo CD → INT; sonra PR ile PROD
- [ ] YAPMAN GEREKEN: Actions'ın PR açmasına izin (Settings > Actions > General > Workflow permissions). Kapalı olduğu için `promote-prod` dalı push edip PR adımında kırılıyor; 1.0.1'in PR'ları (#16, #17, #18) elle açıldı

Durum: tamamlandı (2026-10-02). Not: [tr/07-helm-ve-gitops.md](tr/07-helm-ve-gitops.md). `helm lint` beş chart'ta temiz, `kubectl --dry-run=server` dördünde geçti, `terraform validate` temiz. Argo CD ilk kurulumda 8 Application'ı 3 dakikada Synced/Healthy yaptı. `make cluster-check` 25 kontrolle doğruluyor: iki ortamın ana sayfası ve `/env.json` doğru etiketle, harita özeti ve kaynak durumu 200, sınır dosyası 475 KB, SSE Ingress üzerinden bağlanıyor (`:bagli`, `retry:3000`, 15 sn kalp atışı), üç sertifika kendi CA'mızdan, INT'te tarama kapalı PROD'da açık, HPA 1-3. PROD veritabanı canlı taramalarla 18.799 satır; INT boş (orada tarama yok). Küme + compose 4,0 GB RAM. Takıldığım yerler: Ingress'ten `/api` 502 (nginx resolver kısa servis adını çözemiyor, tam alan adı gerekti); küme compose'dan eski kod çalıştırıyordu (1.0.1 bunun için); `applicationSet.enabled` anahtarı yok (replicas 0); `promote-prod.yml` ilk halinde geçersiz YAML'dı (blok skalarda girintisiz satırlar), workflow dosyalarını doğrulayan adım eklendi.

Bitti sayılma koşulu (lokal haliyle karşılandı): `helm lint` ve `helm template` temiz; Argo CD'de int ve prod Synced/Healthy; uygulama alan adı yerine `https://kesinti.localhost` üzerinde canlı.

## [x] Faz 8 - Gözlemlenebilirlik
- [x] kube-prometheus-stack (Terraform ile), 4 GB'a göre kısıldı: k3s'te olmayan bileşenlerin izlenmesi kapalı, 2 gün / 3 GiB retention, her bileşene bellek limiti
- [x] ServiceMonitor'lar: `monitoring` namespace'inden iki ortamdaki collector ve api, dört hedef `up`
- [x] Üç Grafana panosu repoda JSON, ConfigMap + sidecar ile yükleniyor: kaynak sağlığı, uygulama (SSE istemci sayısı ve DB → tarayıcı gecikmesi dahil), küme
- [x] Alarmlar: arıza 30 dk, planlı 3 saat, günlük açık veri 26 saat, tarama hatası, servis toplanamıyor, SSE gecikmesi, api 5xx oranı
- [x] Alarm testi: `make alarm-testi` / `make alarm-testi-bitir` (Argo CD selfHeal'i de kapatıyor)
- [ ] YAPMAN GEREKEN: Telegram bot token ve chat id (`terraform.tfvars`). Boşken alarmlar Alertmanager'da ve `make alerts` çıktısında görünüyor, bildirim gitmiyor

Durum: tamamlandı (2026-10-02). Not: [tr/08-gozlemlenebilirlik.md](tr/08-gozlemlenebilirlik.md). Servislere metrik eklemek gerekmedi, Faz 3'teki metrikler yetti. Grafana https://grafana.localhost üzerinde, üç pano da yüklü (her biri 8 panel). Prometheus'ta dört hedef `up`, yedi alarm kuralı yüklü. Alarmlar yalnızca `kesinti-prod`'a bakıyor: INT'te tarama kapalı olduğundan oradaki değer sıfır kalıyor ve `time() - 0` 29 milyon dakika veriyor. İzleme yığınıyla birlikte toplam bellek 5,2 GB (compose kapalı). Takıldığım yerler: Grafana 256 MiB ve 60 saniyelik liveness probe ile açılamıyordu (384 MiB + 120 saniye); Helm Grafana'yı beklerken WSL yanıt vermedi ve Terraform state kilidi kaldı (çözüm: çalışan süreci kontrol et, Grafana'yı sağlıklı hale getir, apply kendi bitiyor); `job` etiketi Service adından geliyor (`api`, `collector`); `AlertmanagerConfig`'in `chatID` alanı secret'tan okunamıyor.

## [ ] Faz 9 - v1.1 ve dayanıklılık
- [ ] Doğalgaz kaynağı: BEKLEMEDE. İGDAŞ robots.txt `Disallow: /`, İBB'de İGDAŞ kesinti verisi yok, Başkentgaz sitesinde kesinti yayınlamıyor, İzmirgaz sadece sokak bazlı sorgu veriyor (ayrıntı: [tr/01-kesif-ve-iskelet.md](tr/01-kesif-ve-iskelet.md)). Faz 9 başında yeniden bakılacak; kaynak bulunursa: collector, doğalgaz filtresi ve rengi, CHANGELOG, INT'e otomatik, PROD'a PR ile. Bulunamazsa v1.1'in yeni kaynağı ASKİ (Ankara canlı su arızaları, 2026-09-13 onaylandı); aynı hattan geçecek.
- [x] Doğalgaz ve ASKİ dahil bütün adaylar yeniden yoklandı (2026-10-02): hiçbiri nazik taramaya uygun değil, tablo [tr/09-v11-ve-dayaniklilik.md](tr/09-v11-ve-dayaniklilik.md) içinde. Harita altı kaynakta kalıyor
- [x] k6 ani trafik senaryosu (`loadtest/spike.js`, `make loadtest`), HPA ve cache ölçümü, sonuçlar docs'ta
- [x] Rollback denemesi: git üzerinden 1.0.1 → 1.0.0 → 1.0.1, davranış farkıyla doğrulandı
- [x] Demo runbook'u: [tr/demo-runbook.md](tr/demo-runbook.md) ve [en/demo-runbook.md](en/demo-runbook.md)

Durum: tamamlandı (2026-10-02). Not: [tr/09-v11-ve-dayaniklilik.md](tr/09-v11-ve-dayaniklilik.md). Yük testi: 16.661 istek, 0 hata, 55 istek/sn, 100 sanal kullanıcı; özet ucu p95 6,73 ms (10 dakikalık Redis cache), ilçe listesi p95 7,15 ms (veritabanına giden uç, kısmi indekslerle). HPA sıçramadan 90 saniye sonra karar verdi, 21 saniye içinde 3 replikaya çıktı (tepe CPU %196), test sonrası tek replikaya döndü. Geri alma: push'tan 30 saniye sonra bütün pod'lar 1.0.0'da ve derin sayfa isteği 200 (eski davranış), geri dönüşte 34 saniye ve 400. Yeni kaynak çıkmadı: doğalgaz hâlâ `Disallow: /`, ASKİ ve UEDAŞ form tabanlı, Başkent EDAŞ reCAPTCHA'lı. Takıldığım yerler: `yq` lokalde yok (python ile düzenledim), kirli dizinde rebase (autostash), ilk geri alma ölçümü rollout ortasında yanlış sonuç verdi, ölü host adlarını DoH ile doğruladım.

Bitti sayılma koşulu: v1.1 yeni kaynakla çıkamadı (yukarıdaki tablo), kalan maddeler karşılandı: k6 sonuçları (istek/sn, p95, hata oranı, pod sayısı) docs'ta; geri alma ve ileri alma adım adım denendi ve davranış farkıyla doğrulandı; runbook demo senaryosunu komut komut kapsıyor.
