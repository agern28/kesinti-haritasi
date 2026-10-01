# Faz 7 - Helm ve GitOps

Faz 6'da boş bir k3s kümesi vardı. Bu fazda uygulama oraya kuruldu: dört Helm chart'ı, Argo CD, iki ortam (`kesinti-int` ve `kesinti-prod`) ve yeni sürümü INT'e taşıyıp PROD'a terfi ettiren CI hattı. Hepsi lokalde; bulut yolu için gereken fark [06-altyapi.md](06-altyapi.md) sonunda duruyor.

Adresler:

| Ne | Adres |
|---|---|
| PROD | https://kesinti.localhost |
| INT | https://int.kesinti.localhost |
| Argo CD | https://argocd.localhost (kullanıcı `admin`, parola: `make argocd-password`) |

Sertifikalar Faz 6'daki kendi CA'mızdan, bu yüzden tarayıcı uyarı veriyor; zincir gerçek, `openssl` ile görünüyor.

## Chart'lar

`helm/collector`, `helm/api`, `helm/frontend` ve `helm/data`. Hepsinde aynı kalıp:

- Probe'lar servislerin zaten sunduğu health gruplarına bağlı: readiness veritabanı ve Redis'e bakıyor, liveness bakmıyor. Yani Redis gidince pod trafik almıyor ama yeniden başlatılmıyor. Bu ayrım Faz 3'te `application.yml`'de yapılmıştı, chart sadece doğru yolu çağırıyor.
- Resource request ve limit her konteynerde. Toplam, 2 vCPU / 4 GB'lik bir sunucuya sığacak şekilde seçildi (CLAUDE.md kuralı).
- Root olmayan kullanıcı: Java imajlarında 10001, nginx-unprivileged'da 101. `readOnlyRootFilesystem` açık; JVM'in `/tmp`'i ve nginx'in `conf.d`, `cache` dizinleri `emptyDir`.
- `JAVA_TOOL_OPTIONS` imajdaki varsayılanla aynı (`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`) ama values'tan değiştirilebiliyor.

Servise özel kısımlar:

- **Ingress sadece frontend'de.** nginx `/api`'yi aynı origin'de vekilliyor (compose'daki davranışın aynısı), böylece SSE için ikinci bir giriş noktası ve CORS ayarı gerekmiyor. TLS Ingress'te bitiyor, küme içinde http konuşuluyor.
- **HPA api'de**: CPU %70, 1-3 replika. `scaleDown.stabilizationWindowSeconds: 300`, çünkü SSE bağlantıları uzun yaşıyor ve pod'u hemen kapatmak tarayıcıları yeniden bağlanmaya zorlar. `terminationGracePeriodSeconds: 45` aynı sebepten.
- **collector tek replika.** İkinci replika aynı kaynağa iki kez gider; ölçeklenmesi gereken servis api.

## Veri katmanı

`helm/data`: kümede tek PostgreSQL ve tek Redis, `kesinti-data` namespace'inde. Ortamlar şöyle ayrılıyor:

- PostgreSQL'de ayrı veritabanı: `kesinti_int` ve `kesinti_prod`. İkisini `docker-entrypoint-initdb.d`'ye konan bir script oluşturuyor, zaten varsa dokunmuyor.
- Redis'te ayrı logical DB: int 0, prod 1. Stream ve cache anahtarları aynı isimde ama ayrı veritabanında.

İkisi de StatefulSet ve k3s'in `local-path` provisioner'ıyla PVC alıyor. Operator yok, replikasyon yok, yedek yok: lokal bir kümede ve 4 GB hedefinde bunların maliyeti faydasından fazla. Buluta geçişte burası değişir (yönetilen PostgreSQL ya da bir operator).

PostgreSQL parolası repoda değil: Terraform `random_password` ile üretip üç namespace'teki `kesinti-db` secret'ına yazıyor, chart'lar `secretKeyRef` ile okuyor. Parola Terraform state'inde duruyor, state `.gitignore`'da. Bulutta bunun yerine harici bir secret deposu gerekir.

## Argo CD ve app-of-apps

Argo CD'yi Terraform kuruyor (`infra/terraform/local/argocd.tf`): dex ve notifications kapalı, ApplicationSet sıfır replika, kalan bileşenlere bellek limiti. `server.insecure=true`, çünkü TLS'i Traefik bitiriyor.

Uygulamaları Terraform kurmuyor. Terraform sadece kök Application'ı kuruyor (`helm/argocd-root`), o da repodaki `gitops/apps` dizinini izliyor. Oradaki her dosya bir Argo CD Application:

```
gitops/apps/data.yaml            -> kesinti-data
gitops/apps/int-collector.yaml   -> kesinti-int
gitops/apps/int-api.yaml
gitops/apps/int-frontend.yaml
gitops/apps/prod-collector.yaml  -> kesinti-prod
gitops/apps/prod-api.yaml
gitops/apps/prod-frontend.yaml
```

Yeni bir servis ya da ortam eklemek için kümeye elle bir şey uygulanmıyor, repoya dosya ekleniyor.

Her Application iki kaynaklı (multi-source): biri chart'ın yolu (`helm/api`), diğeri aynı repoya `ref: values` olarak bağlanıp values dosyasını veriyor (`$values/gitops/int/api.yaml`). Böylece chart tek yerde duruyor, ortam farkları `gitops/int` ve `gitops/prod` altında kalıyor:

| Değer | INT | PROD |
|---|---|---|
| veritabanı | `kesinti_int` | `kesinti_prod` |
| Redis logical DB | 0 | 1 |
| collector taraması | kapalı | açık |
| HPA | kapalı, tek pod | açık, 1-3 |
| `APP_ENV` | INT | PROD |
| adres | int.kesinti.localhost | kesinti.localhost |

Sync politikası ikisinde de otomatik (`prune` ve `selfHeal` açık). Namespace'leri Argo CD oluşturmuyor (`CreateNamespace=false`), onlar Terraform'un işi.

## INT kaynak sitelere gitmiyor

CLAUDE.md'deki nezaket kuralı: bir kaynağa aynı anda tek yerden gidilir. İki ortamın collector'ı da tararsa kaynak siteler iki kat istek görür. Bu yüzden `gitops/int/collector.yaml`'da `scheduling.enabled: false`: INT'te servis ayakta, sağlığı ve metrikleri görünüyor, sadece zamanlanmış taramalar kapalı. Tarayan tek collector PROD'daki.

Aynı sebeple compose'daki collector durduruldu (`docker compose stop collector`). `make cluster-check` bunu da kontrol ediyor: compose collector çalışıyorsa kontrol kırmızı veriyor.

## Terfi hattı

İki workflow eklendi.

**promote-int.yml**: bir servis tag'i atıldığında (`api-v1.0.1`) `gitops/int/api.yaml` içindeki `image.tag` güncellenir ve `main`'e commit edilir. Argo CD o commit'i görüp INT'e kurar. Elle de çalıştırılabiliyor (`workflow_dispatch`, servis ve sürüm seçilerek).

Dal kurallarıyla çakışma sorusu: bu workflow `GITHUB_TOKEN` ile doğrudan `main`'e push ediyor. `main` korumalı hale gelirse (zorunlu review ya da status check) doğrudan push reddedilir; o durumda adımı PR açmaya çevirmek gerekir, `promote-prod.yml`'deki yöntemin aynısı. Sonsuz döngü riski yok: hiçbir workflow `gitops/**` yolunu dinlemiyor, yani bu commit yeni bir koşu başlatmıyor.

**promote-prod.yml**: elle tetiklenir. INT'te duran sürümü `gitops/prod/<servis>.yaml`'a yazan bir PR açar. PROD'a geçiş bir karar olduğu için otomatik değil; PR birleşince Argo CD PROD'u o sürüme geçirir.

## 1.0.1: hattın gerçekten çalıştırılması

Kurulum bittiğinde `make cluster-check` bir şeyi yakaladı: derin sayfa isteği compose'da 400 dönüyordu, kümede 200. Sebep, kümedeki imajların GHCR'daki `1.0.0` etiketli imajlar olması; onlar v1.0.0 tag'inde, sertleştirme turundan önce derlenmişti. Yani küme, compose'dan eski kod çalıştırıyordu.

Doğru çözüm yeni sürüm çıkarmaktı, bu da terfi hattını baştan sona denemek demek: CHANGELOG'a 1.0.1 bölümü (iki dilde), üç servis için `-v1.0.1` tag'i, CI'ın imajları derleyip GHCR'a göndermesi ve Release açması, `promote-int`'in INT values'larını güncellemesi, Argo CD'nin INT'e kurması, sonra `promote-prod` ile PROD'a terfi.

## Doğrulama

`make cluster-check` 25 kontrol yapıyor: Argo CD'de 8 Application'ın hepsi Synced/Healthy, üç namespace'te pod'lar Running, üç sertifika hazır ve kendi CA'mızdan, iki ortamın ana sayfası ve `/env.json`'u doğru etiketle, harita özeti ve kaynak durumu 200, ilçe sınırları dosyası tam boyutta, SSE Ingress üzerinden bağlanıyor, iki ortam veritabanı var, PROD'da veri birikiyor, INT'te tarama kapalı PROD'da açık, compose collector kapalı, HPA 1-3.

Ölçülenler:

- Argo CD ilk kurulumda 8 Application'ı 3 dakikada Synced/Healthy yaptı.
- PROD collector açıldıktan sonraki ilk turda İSKİ dosyasından 18.379 yeni kayıt yazdı; PROD veritabanı 18.799 satır.
- INT api boş veritabanıyla özet sorgusuna 8 ms'de cevap veriyor.
- SSE Ingress üzerinden compose'daki davranışın aynısı: `:bagli`, `retry:3000`, 15 saniyede bir kalp atışı.
- Küme + compose'un kalanı toplam 4,0 GB RAM (WSL'e verilen 7,6 GB içinde).

## Takıldığım yerler

- **Ingress'ten `/api` 502.** Statik dosyalar geliyordu, api istekleri 502. Sebep nginx'in `resolver` direktifinin `/etc/resolv.conf`'taki search listesini kullanmaması: `http://api:8080` adresindeki kısa adı CoreDNS'e olduğu gibi soruyor ve NXDOMAIN alıyor. Compose'da Docker'ın DNS'i kısa adı çözdüğü için sorun çıkmamıştı. Chart artık `API_UPSTREAM`'i boş bırakılınca `http://api.<namespace>.svc.cluster.local:8080` olarak üretiyor.
- **Küme eski kod çalıştırıyordu** (yukarıdaki 1.0.1 bölümü). Ders: imaj etiketi bir commit'i değil, bir tag'i gösteriyor; "en son kod kurulu" varsayımı yanlış.
- **`applicationSet.enabled` yok.** Argo CD chart 10.x'te ApplicationSet'i kapatan bir anahtar yok; `applicationSet.replicas=0` ile sıfıra çekildi. Helm bilinmeyen değerlere hata vermediği için ilk denemede sessizce kurulmuştu.
- **Argo CD varsayılan olarak 3 dakikada bir repoyu kontrol ediyor.** Düzeltme push edildiğinde hemen görmedim; `make argocd-refresh` (hard refresh annotation'ı) beklemeden tetikliyor.
- **İlk istek 504.** Yeni pod açıldıktan sonraki ilk `/api/map/summary` isteği zaman aşımına düştü, sonraki istekler 8 ms. nginx'in upstream'i ilk kez çözmesi ve JVM'in ısınması. Probe'lar hazır dediği an trafik gelebildiği için beklenen davranış; gerçek bir yük altında ısıtma isteği gerekebilir.
- **`psql -U kesinti` çalışmıyor.** Kullanıcı adıyla aynı isimde veritabanı olmadığı için `-d postgres` gerekiyor. Kendi kontrol script'imdeki hataydı, kümede değil.
