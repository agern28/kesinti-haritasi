# Kesinti Haritası - Proje Planı

Türkiye genelindeki elektrik, su ve doğalgaz kesintilerini tek bir haritada gösteren uygulama. Resmi kaynaklardaki planlı/arıza duyuruları otomatik toplanıyor, il > ilçe > mahalle diye yakınlaşan bir haritada kesinti türüne göre renklendiriliyor. İleriki sürümlerde kullanıcı bildirimleri ("bende de yok"), adres bazlı uyarı ve mahalle karnesi geliyor.

Staj açısından amaç: Hafta 1-4'te öğrenilen her şeyi (Git, Linux, Docker, CI/CD, Kubernetes, Helm, probe/HPA) gerçek kullanıcıya açık, bulutta çalışan bir üründe birleştirmek. Ek olarak stajda olmayan üç şey: Terraform ile altyapı, Argo CD ile GitOps, Prometheus/Grafana ile gözlemlenebilirlik.

## Mevcut sitelerden farkı

Benzer siteler var ama çoğu il il liste. Buradaki fark:
- gerçek harita, canlı güncelleniyor
- kullanıcı bildirimleriyle "sadece bende mi" sorusunun cevabı
- geçmiş veriden mahalle karnesi (son 6 ayda kaç kesinti, ortalama süre)
- her kaynağın sağlığı izleniyor, kaynak bozulunca alarm var

## Mimari

```
   [BEDAŞ]   [İSKİ / İBB Açık Veri]   ...
        \         /
      collector-service  (Spring Boot, zamanlanmış görevler)
              |
        Redis Streams  (outage-events)
              |
        outage-api  (Spring Boot, REST + SSE)  --- PostgreSQL
              |                                 --- Redis (cache)
          frontend  (React + Leaflet, nginx container)
```

### collector-service
- Her kaynak ayrı bir sınıf: `SourceCollector` arayüzü, `BedasCollector`, `IskiCollector` (İBB Açık Veri)... Hangi kaynağın neden alındığı ya da alınmadığı (AYEDAŞ, İGDAŞ, Başkentgaz, İzmirgaz): [tr/01-kesif-ve-iskelet.md](tr/01-kesif-ve-iskelet.md).
- Yeni şehir/kurum eklemek = yeni bir sınıf + testi. Sürüm sürüm büyüme buradan geliyor.
- Tarama sıklığı kaynağın güncellenme sıklığına göre, en sık 5 dakika: arıza/anlık kesinti sayfaları 5 dakikada, planlı kesinti duyuruları 15 dakikada, İBB Açık Veri'deki İSKİ veri seti günde bir. Veriyi ortak modele çevirir (normalize), sadece yeni/değişen/biten kayıtları Redis Stream'e yazar.
- Parser testleri canlı siteye gitmez: her kaynaktan kaydedilmiş örnek HTML/JSON dosyaları (`src/test/resources/fixtures/`) üzerinden çalışır. Site tasarımı değişince önce test kırılır.
- Prometheus metrikleri:
  - `collector_last_success_timestamp{source}`
  - `collector_items_total{source}`
  - `collector_errors_total{source}`

### outage-api
- Stream'i okur, kesintiyi veritabanına yazar. Aynı kesinti her taramada tekrar geldiği için `dedup_key` ile tekilleştirilir: kaynak kendi id'sini veriyorsa (`external_id`) anahtar `kaynak + external_id`, vermiyorsa kaynak + ilçe + başlangıç + mahalle listesinin hash'i. Ayrıntı: [tr/veri-modeli.md](tr/veri-modeli.md).
- Endpoint'ler:
  - `GET /api/outages?type=&il=&ilce=&active=true`
  - `GET /api/map/summary` - il/ilçe bazında aktif kesinti sayıları (harita renklendirme, Redis'te cache)
  - `GET /api/outages/{id}`
  - `GET /api/sources` - her kaynağın son başarılı tarama zamanı (arayüzdeki "son güncelleme" göstergesi)
  - `GET /api/stream` - SSE; kesinti eklendiği, değiştiği veya bittiği an tarayıcıya gider, harita sayfayı yenilemeden güncellenir. Birden fazla pod olduğu için olaylar Redis Pub/Sub ile tüm pod'lara dağıtılır.
  - v1.3: `POST /api/reports` - kullanıcı bildirimi
- `/actuator/health`, `/actuator/prometheus` (probe'lar ve metrikler için)

### frontend
- React + Leaflet. Türkiye il/ilçe sınırları açık kaynak GeoJSON dosyasından.
- v1: ilçe seviyesinde renklendirme + tıklanınca etkilenen mahallelerin listesi. Mahalle poligonu işi sonraya.
- Köşede sürüm ve ortam etiketi (`v1.2.0 - INT`), "Yenilikler" penceresi changelog'dan.

### Veri modeli (ilk hali)

```
outage
  id              uuid
  source          varchar   -- BEDAS, ISKI, ... (kaynak listesi: tr/01-kesif-ve-iskelet.md)
  external_id     varchar   -- kaynağın kendi id'si (BEDAŞ: plannedOutage.id, OUTAGE_NO); yoksa null
  type            varchar   -- ELECTRICITY, WATER, GAS
  planned         boolean
  il              varchar
  ilce            varchar
  mahalleler      text[]
  starts_at       timestamptz
  ends_at         timestamptz
  reason          text
  source_url      text
  lat             double precision  -- null olabilir (BEDAŞ planlılarda var)
  lon             double precision  -- null olabilir
  dedup_key       varchar unique
  first_seen_at   timestamptz
  last_seen_at    timestamptz
```

Tekilleştirme: `external_id` doluysa `dedup_key = <source>:<external_id>`, boşsa `dedup_key = <source>:h:` + (kaynak + ilçe + başlangıç + sıralı mahalle listesi) hash'i. Ek olarak `(source, external_id)` üzerinde `external_id IS NOT NULL` koşullu unique index var. Ayrıntılı tablo ve kurallar: [tr/veri-modeli.md](tr/veri-modeli.md) / [en/data-model.md](en/data-model.md).

Aktif kesinti = `now()` başlangıç ve bitiş arasında. Kaynaktan kaybolan ama bitiş saati gelmemiş kayıtlar `last_seen_at` ile takip edilir.

## Altyapı

- Sunucu: Hetzner CX23 (2 vCPU / 4 GB). Terraform `hcloud` provider ile açılır, cloud-init veya Ansible ile k3s kurulur. `terraform destroy` + `apply` ile sıfırdan cluster demosu yapılabilir.
- Ingress: k3s ile gelen Traefik. cert-manager + Let's Encrypt ile HTTPS. Domain gerekiyor (ucuz bir alan adı ya da başlangıç için nip.io/duckdns).
- Ortamlar: `int` ve `prod` namespace'leri. 4 GB'ta Argo CD + Prometheus/Grafana + iki ortam sığar, üçüncü ortam (UAT) sıkışık olur. UAT istenirse sunucu bir üst boyuta çıkarılır.
- PostgreSQL ve Redis cluster içinde (Helm chart, küçük kaynak limitleri). Tek Postgres instance, ortam başına ayrı veritabanı.
- Secret'lar repoda yok, `secret.example.yaml` mantığı Hafta 4'teki gibi devam (ileride Sealed Secrets).

## CI/CD

GitHub Actions, her servis için ayrı workflow (`paths` filtresi ile sadece değişen servis build olur):
1. build + test (Maven cache, Postgres/Redis gerektiren testler için Testcontainers)
2. coverage (JaCoCo, eşik)
3. SonarQube Cloud quality gate (public repo için ücretsiz)
4. Docker image build + Trivy taraması (kritik açık varsa kırılır)
5. tag atılınca (`collector-v1.1.0`, `api-v1.1.0`) GHCR'a push + GitHub Release

GitOps (Argo CD):
- `gitops/` klasöründe ortam başına values dosyaları: `values-int.yaml`, `values-prod.yaml`
- Yeni image çıkınca pipeline INT values'taki tag'i günceller, Argo CD INT'e otomatik alır
- PROD'a geçiş: INT'teki tag'i PROD values'a taşıyan PR, onayla merge, Argo CD senkronlar
- Rollback: PROD values'ta eski tag'e dönen commit (veya Argo CD üzerinden eski revizyon)

## Gözlemlenebilirlik

- kube-prometheus-stack (kaynak limitleri düşürülmüş halde)
- Grafana panelleri:
  - kaynak sağlığı: her kaynağın son başarılı taraması, bulunan kayıt sayısı, hata sayısı
  - uygulama: istek sayısı, gecikme, hata oranı, cache hit oranı
  - cluster: pod sayısı, CPU/bellek, HPA durumu
- Alarm: arıza sayfalarından 30 dakika, planlı duyurulardan 3 saat, günlük açık veri kaynaklarından 26 saat boyunca başarılı tarama gelmezse Telegram bildirimi

## Repo yapısı

```
kesinti-haritasi/
  README.md / README.en.md
  services/
    collector/        Spring Boot
    api/              Spring Boot
  frontend/           React + Leaflet
  helm/
    collector/
    api/
    frontend/
  gitops/
    apps/             Argo CD Application tanımları
    int/              values-int
    prod/             values-prod
  infra/
    terraform/        Hetzner sunucu, firewall, DNS
    ansible/          k3s kurulumu (cloud-init yetmezse)
  loadtest/           k6 senaryoları
  docs/
    tr/               gün gün notlar
    en/
  .github/workflows/
  docker-compose.yml  lokal geliştirme (postgres, redis, servisler)
```

## Veri toplama kuralları

- Tarama sıklığı kaynağın güncellenme sıklığına göre belirlenir, en sık 5 dakika: arıza sayfaları 5 dakikada, planlı duyurular 15 dakikada, açık veri setleri günde bir; isteklere rastgele küçük gecikme (jitter), kaynak başına tek istek dizisi, agresif tarama yok
- `robots.txt` her istekten önce kodla kontrol edilir: yasak yola istek atılmaz, `Crawl-delay` varsa ona uyulur. User-Agent'ta proje adı ve iletişim bilgisi
- Captcha, gömülü token, WAF gibi erişim kontrolleri aşılmaz; böyle bir kaynak v1'e alınmaz
- Seyrek güncellenen açık veri setleri (ör. İBB'deki İSKİ su kesintileri, yılda bir dosya) günde bir kontrol edilir; yeni dosya yoksa indirme yapılmaz
- Haritada her kesintinin yanında kaynak adı ve orijinal duyuru linki
- Gün 1'de her kaynağın verisinin nasıl sunulduğu (HTML tablo, JSON endpoint, form arkası) çıkarılır. Bir kaynak zor çıkarsa v1'den çıkarılıp sonraki sürüme bırakılır.

## 2 haftalık plan

| Gün | İş | Çıktı |
|---|---|---|
| 1 | Kaynak keşfi (BEDAŞ, AYEDAŞ, İSKİ, İGDAŞ), veri modeli, repo iskeleti, docker-compose | kaynak notları, boş servisler ayağa kalkıyor |
| 2 | collector: BEDAŞ (planlı + arıza), fixture testleri | İstanbul Avrupa yakası elektrik verisi normalize ediliyor |
| 3 | collector: İSKİ (İBB Açık Veri); Redis Streams; api tarafında tüketme, dedup, Postgres | veriler veritabanında |
| 4 | api endpoint'leri + frontend harita (ilçe renklendirme, liste, SSE) | lokalde çalışan harita |
| 5 | Dockerfile'lar, CI (test, coverage, Sonar, Trivy, GHCR) | `v1.0.0` image'ları GHCR'da |
| 6 | Terraform ile Hetzner + k3s, domain, HTTPS | boş cluster internetten erişilebilir |
| 7 | Helm chart'ları, Argo CD, INT/PROD, PR ile terfi | v1.0 canlıda |
| 8 | Prometheus/Grafana, collector metrikleri, kaynak sağlığı paneli, Telegram alarmı | paneller + alarm testi |
| 9 | v1.1: yeni kaynak (doğalgaz kaynağı bulunursa o, bulunamazsa senin seçtiğin kaynak) tüm hattan geçer; k6 yük testi, HPA, cache ölçümü | v1.1 canlıda, yük testi sonuçları |
| 10 | v1.2 (yeni şehir veya kullanıcı bildirimi), rollback denemesi, demo runbook, README | demo hazır |

## Sonraki sürümler

- v1.2: Ankara (Başkent EDAŞ, ASKİ, Başkentgaz) ve İzmir (GDZ, İZSU, İzmirgaz)
- v1.3: kullanıcı bildirimi ("bende de yok"), IP başına limit, aynı bölgeden bildirimler birikince haritada işaret
- v2.0: adres kaydı ve planlı kesinti uyarısı (e-posta/Telegram, bildirimler kuyruktan)
- v2.1: mahalle karnesi, Türkiye geneli istatistik sayfası. İSKİ'nin İBB Açık Veri'deki geçmiş su kesintisi verisi (2022-2024, mahalle bazında) karnenin su tarafında kullanılacak; bu veri v1'den itibaren günlük toplanıp saklanıyor ama canlı haritada aktif kesinti olarak görünmüyor.
- sonra: mahalle poligonları, internet sağlayıcı kesintileri, açık API

## Demo senaryosu (5-7 dk)

1. Canlı harita: şu an Türkiye'de nerede kesinti var
2. Grafana: kaynak sağlığı paneli, bir kaynağı bilerek bozup alarmın Telegram'a düşmesi
3. Küçük bir değişiklik commit'lenir, pipeline'dan geçer, INT'te görünür
4. PROD'a terfi PR'ı merge edilir, PROD'da yeni sürüm ve "Yenilikler" penceresi açılır
5. k6 ile ani trafik: pod sayısı artar, hata oranı düşük kalır
6. Tek komutla eski sürüme dönüş
