# Kesinti Haritası

Türkiye genelindeki elektrik, su ve doğalgaz kesintilerini resmi kaynaklardan toplayıp haritada gösteren uygulama. Aynı zamanda bir DevOps staj projesi: amaç uygulamanın kendisi kadar, onu bulutta nasıl işlettiğimizi göstermek.

Tam plan: docs/plan.md. Fazların tam tanımı (9 faz, orijinal metin + kararlarla değişenler): docs/tr/fazlar.md. İlerleme: docs/PROGRESS.md. Her oturuma bu üç dosyayı okuyarak başla. Faz tanımları kısaltılmaz ve silinmez; karar değişiklikleri fazlar.md'deki tabloya eklenir.

## Teknoloji
- Backend: Java 21+, güncel kararlı Spring Boot, Maven
- services/collector: kaynaklardan veri toplar, Redis Streams'e yazar
- services/api: stream'i tüketir, PostgreSQL'e yazar, REST + SSE sunar
- frontend: React + Vite + Leaflet, nginx container ile servis edilir
- Altyapı: Hetzner (Terraform), k3s, Traefik, cert-manager, Helm, Argo CD, kube-prometheus-stack
- CI: GitHub Actions, SonarQube Cloud, Trivy, GHCR

## Kurallar
- Her iş küçük, anlamlı commit'lerle ilerler. Conventional commits (feat:, fix:, ci:, docs:, infra:).
- Bir faz bitmeden sonrakine geçme. Faz bitince docs/PROGRESS.md'yi güncelle ve dur.
- Test yazmadan collector parser'ı yazma. Parser testleri canlı siteye gitmez, src/test/resources/fixtures altındaki kayıtlı örneklerle çalışır.
- Kaynak sitelere nazik ol: keşif için kaynak başına birkaç istek; üretimde tarama sıklığı kaynağın güncellenme sıklığına göre belirlenir, en sık 5 dakika (tipik değerler: arıza/anlık kesinti sayfaları 5 dakika, planlı kesinti duyuruları 15 dakika, seyrek güncellenen açık veri setleri günde bir); anlamlı User-Agent, robots.txt kontrolü.
- Gerçek zamanlılık: veritabanına yeni/değişen/biten bir kesinti düştüğü an SSE ile tarayıcıya gider. Kullanıcı sayfayı yenilemeden görür.
- Secret, token, parola, kubeconfig asla repoya girmez. Örnek dosyalar *.example uzantısıyla.
- Sunucu 2 vCPU / 4 GB. Her pod'a resource request/limit ver, JVM'lerde -XX:MaxRAMPercentage kullan, gereksiz bileşen kurma.
- terraform apply, helm install/upgrade, kubectl apply gibi gerçek altyapıyı değiştiren komutları çalıştırmadan önce ne yapacağını söyle ve onay bekle.
- Benden bir şey gerekiyorsa (hesap, token, DNS, secret) açıkça "YAPMAN GEREKEN:" başlığıyla adım adım yaz ve dur.

## Dokümantasyon kuralları
- Her markdown dokümanı iki dilde: Türkçe ve İngilizce (docs/tr/..., docs/en/...; README.md Türkçe, README.en.md İngilizce).
- Markdown dosyalarında emoji yok.
- Aşırı resmi ya da yapay zeka yazmış gibi duran dil yok. Bir mühendisin kendi notları gibi yazılır: ne yaptım, neden böyle yaptım, ne sonuç aldım, nerede takıldım.
- Her faz için docs/tr/NN-faz-adi.md ve docs/en/NN-phase-name.md.
