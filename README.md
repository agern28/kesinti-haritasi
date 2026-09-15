# Kesinti Haritası

[![collector](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/collector.yml)
[![api](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/api.yml)
[![frontend](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml/badge.svg)](https://github.com/agern28/kesinti-haritasi/actions/workflows/frontend.yml)

İstanbul'dan başlayarak Türkiye'deki elektrik, su ve doğalgaz kesintilerini resmi kaynaklardan toplayıp tek bir haritada gösteren uygulama. Harita canlı: yeni bir kesinti veritabanına düştüğü an sayfa yenilenmeden görünüyor.

Proje aynı zamanda bir DevOps staj projesi. Uygulamanın kendisi kadar onu nasıl işlettiğimiz de işin parçası: CI, container, Terraform ile Hetzner, k3s, Helm, Argo CD ile GitOps, Prometheus/Grafana ile izleme.

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
| `helm` | Servislerin Helm chart'ları |
| `gitops` | Argo CD Application'ları ve ortam values dosyaları |
| `infra` | Terraform (Hetzner) ve gerekirse Ansible |
| `loadtest` | k6 senaryoları |
| `docs` | Faz notları (`tr/`, `en/`) |

## Lokal çalıştırma

Gerekenler: Docker ve Docker Compose. Java ve Node sadece servisleri container dışında geliştirmek için lazım (Java 21, Maven 3.9, Node 22.12+).

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
