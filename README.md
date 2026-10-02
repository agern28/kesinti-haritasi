# Kesinti Haritası

[![collector](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml)
[![api](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml)
[![frontend](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml)
[![compose-smoke](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/compose-smoke.yml)
[![infra](https://github.com/agern28/kesinti-haritasi/actions/workflows/infra.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/infra.yml)

İstanbul'dan başlayarak Türkiye'deki elektrik, su ve doğalgaz kesintilerini resmi kaynaklardan toplayıp tek bir haritada gösteren uygulama. Harita canlı: yeni bir kesinti veritabanına düştüğü an sayfa yenilenmeden görünüyor.

Proje aynı zamanda bir DevOps staj projesi. Uygulamanın kendisi kadar onu nasıl işlettiğimiz de işin parçası: CI, container, Terraform, k3s (şimdilik lokalde, k3d ile), Helm, Argo CD ile GitOps, Prometheus/Grafana ile izleme.

English: [README.en.md](README.en.md)

## Mimari

```
kaynak siteler (BEDAŞ, AYEDAŞ, İSKİ, ...)
        |
   collector  (Spring Boot, zamanlanmış taramalar)
        |
   Redis Streams  (outage-events)
        |
   api  (Spring Boot, REST + SSE)  ---  PostgreSQL, Redis cache
        |
   frontend  (React + Leaflet, nginx)
```

Ayrıntılı plan: [docs/plan.md](docs/plan.md). İlerleme: [docs/PROGRESS.md](docs/PROGRESS.md).

## Repo yapısı

| Klasör | İçerik |
|---|---|
| `services/collector` | Kaynaklardan veri toplayan servis |
| `services/api` | REST + SSE API |
| `frontend` | React + Vite + Leaflet arayüzü |
| `helm` | Servislerin Helm chart'ları ve `cluster-issuers` (cert-manager issuer'ları) |
| `gitops` | Argo CD Application'ları ve ortam values dosyaları |
| `infra` | `k3d/` lokal k3s küme tanımı, `terraform/local` küme üstü kurulum |
| `loadtest` | k6 senaryoları |
| `docs` | Faz notları (`tr/`, `en/`) |

## Lokal çalıştırma

Gerekenler: Docker ve Docker Compose. Java ve Node sadece servisleri container dışında geliştirmek için lazım (Java 21, Maven 3.9, Node 22.12+).

```bash
make up      # .env yoksa rastgele parolayla oluşturur, imajları derler, sağlıklı olmasını bekler
make check   # çalışan stack'i uçtan uca dener
```

Make kullanmak istemezsen aynısının uzun hali:

```bash
cp .env.example .env   # parolayı değiştir, .env repoya girmez
docker compose up --build
```

Ayağa kalkanlar:

| Servis | Adres |
|---|---|
| frontend | http://localhost:3000 |
| api | http://localhost:8080/actuator/health |
| collector | http://localhost:8081/actuator/health |
| postgres | localhost:5432 (kullanıcı adı ve parola `.env` dosyasından) |
| redis | localhost:6379 |

Metrikler her iki serviste `/actuator/prometheus` altında.

### Günlük kullanım

| Komut | Ne yapar |
|---|---|
| `make ps` | Servislerin durumu ve portları |
| `make check` | 15 kontrol: nginx, api yolları, SSE, veritabanı, kaynak sağlığı |
| `make logs-collector` | Hangi kaynak ne zaman tarandı, kaç kayıt geldi |
| `make stop` / `make up` | Durdur / tekrar kaldır |
| `make reset` | Veritabanı ve Redis dahil her şeyi silip baştan kurar |
| `make smoke` | CI'daki uçtan uca testi lokalde koşar (lokal stack kapalı olmalı) |
| `make test` | Servis ve frontend testleri (container dışında) |

Tüm liste: `make help`.

Container'lar `restart: unless-stopped` ile çalışıyor, yani bilgisayar ya da Docker yeniden başlayınca stack kendi kalkar; `make stop` ile durdurduklarınsa kalkmaz. Docker Desktop kullanıyorsan açılışta başlaması için ayarının açık olması gerekir (Settings > General > Start Docker Desktop when you sign in).

Veri `pgdata` ve `redisdata` adlı Docker volume'lerinde duruyor, `make down` onlara dokunmaz. Kesinti geçmişi birikmeye devam eder; sıfırdan başlamak için `make reset`.

Stack kapalı kaldığı sürede kaynaklardaki liste değiştiği için ilk taramalarda büyük NEW/GONE sayıları görmek normaldir.

### Lokal Kubernetes kümesi

Compose'un yanında, aynı makinede bir k3s kümesi var: k3d ile Docker içinde çalışıyor, kümenin üstünü (namespace'ler, cert-manager, sertifika issuer'ları) Terraform kuruyor. Gereken tek ek araç k3d; `kubectl`, `helm` ve `terraform` de lazım.

```bash
make cluster-up      # kümeyi kurar, sonra terraform apply ile üstünü kurar
make cluster-status  # düğümler, pod'lar, issuer'lar
make cluster-down    # kümeyi tamamen siler
```

Uygulama kümeye Argo CD ile kuruluyor: repodaki `gitops/apps` altındaki Application'lar iki ortamı ayağa kaldırıyor.

| Ne | Adres |
|---|---|
| PROD | https://kesinti.localhost |
| INT | https://int.kesinti.localhost |
| Argo CD | https://argocd.localhost (`admin`, parola: `make argocd-password`) |
| Grafana | https://grafana.localhost (`admin`, parola: `make grafana-password`) |

Sertifikalar kümedeki kendi CA'mızdan, bu yüzden tarayıcı uyarı verir; "yine de devam et" diyebilirsin.

```bash
make cluster-check     # kümedeki kurulumu uçtan uca dener
make argocd-apps       # Application'ların sync ve sağlık durumu
make argocd-refresh    # Argo CD'ye repoyu hemen kontrol ettir
make alerts            # alarm kurallarının durumu
make prometheus        # Prometheus arayüzü (localhost:9090)
```

İzleme: Prometheus, Alertmanager ve Grafana kümede çalışıyor; üç pano repoda JSON olarak duruyor (kaynak sağlığı, uygulama, küme). Taramalar durduğunda alarm üretiliyor; Telegram'a bildirim için `terraform.tfvars`'a bot token ve chat id yazmak gerekiyor. Ayrıntı: [docs/tr/08-gozlemlenebilirlik.md](docs/tr/08-gozlemlenebilirlik.md).

İki ortamın farkları `gitops/int` ve `gitops/prod` altındaki values dosyalarında: veritabanı, Redis logical DB, HPA, ortam etiketi ve INT'te kapalı olan kaynak taraması. Ayrıntı: [docs/tr/07-helm-ve-gitops.md](docs/tr/07-helm-ve-gitops.md). Kümenin kendisi ve buluta (Hetzner) taşımak için gerekenler: [docs/tr/06-altyapi.md](docs/tr/06-altyapi.md).

Not: kaynak sitelere aynı anda tek yerden gidilir. Küme açıkken tarayan collector PROD'daki; compose'un collector'ını durdur (`docker compose stop collector`).

### Container dışında geliştirme

```bash
# Servis testleri
cd services/collector && mvn verify
cd services/api && mvn verify

# Frontend (api'yi localhost:8080'de bekler)
cd frontend && npm install && npm run dev
cd frontend && npm test

# İlçe sınırlarını yeniden üretmek (python3 ve npx gerekiyor, sonuç public/geo/ilceler.topo.json)
cd frontend && python3 scripts/ilceler.py
```

İlçe sınırları OCHA HDX COD-AB'den (Harita Genel Komutanlığı verisi, CC BY-IGO). Ayrıntı: [docs/tr/04-frontend.md](docs/tr/04-frontend.md).

## Veri kaynakları

Hangi kaynağın nasıl okunduğu ve neden bazılarının şimdilik dışarıda kaldığı: [docs/tr/01-kesif-ve-iskelet.md](docs/tr/01-kesif-ve-iskelet.md).

Kaynaklara nazik davranılıyor: arıza sayfaları 5 dakikada, planlı kesinti duyuruları 15 dakikada bir taranıyor, istekler arasında rastgele gecikme var, User-Agent'ta proje adı ve repo linki var, robots.txt'e uyuluyor.
