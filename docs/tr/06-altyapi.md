# Faz 6 - Altyapı (lokal k3s)

Faz 6'nın orijinal tanımı Hetzner'de bir CX23 sunucu, cloud-init ile k3s ve Let's Encrypt sertifikalarıydı. Proje lokalde çalışacak şekilde ilerlesin diye kümeyi kendi makinede kurduk: aynı k3s, aynı Traefik, ama Docker içinde ve para harcamadan. Hetzner yolu kapanmadı; bu notun sonunda buluta taşımak için gereken tek ek iş yazıyor.

Karar: [fazlar.md](fazlar.md) karar tablosunda 2026-10-02 satırı.

## Küme

`infra/k3d/cluster.yaml`, k3d'nin kendi yapılandırma formatında (`k3d.io/v1alpha5`):

- 1 server + 1 agent. İki düğüm olması pod'ların dağılmasını görmek için; bellek sıkışırsa `agents: 0` da çalışır.
- `image: rancher/k3s:v1.35.5-k3s1` sabit. Kümeyi silip tekrar kurduğumuzda sürüm kaymasın.
- 80 ve 443, k3d load balancer'ı üzerinden localhost'a bağlı. Böylece tarayıcıdan doğrudan `http://kesinti.localhost` açılıyor, port yönlendirme (`kubectl port-forward`) gerekmiyor.
- metrics-server kapatılmadı: Faz 7'deki HPA ona bağlı.
- `--tls-san=host.k3d.internal` eklendi; küme içinden host'a bakan isteklerde sertifika adı tutsun.

Kurma ve silme:

```bash
make cluster-up      # kume + kume ustu kurulum
make cluster-status  # dugumler, pod'lar, issuer'lar
make cluster-down    # k3d cluster delete kesinti
```

`make cluster-down` kümeyi tamamen siler, compose'daki uygulama verisine dokunmaz. Kümeyi silip tekrar kurarsan Terraform state'i eski kalır; sonraki `terraform apply` kaynakları yok sayıp yeniden oluşturur, elle bir şey silmek gerekmiyor.

## Terraform

Küme k3d ile kuruluyor, kümenin üstü Terraform ile: `infra/terraform/local`.

| Kaynak | Ne |
|---|---|
| `kubernetes_namespace_v1.ortamlar` | `kesinti-int` ve `kesinti-prod`. Faz 7'de Argo CD bu namespace'lere kuracak |
| `helm_release.cert_manager` | cert-manager v1.21.2, kendi namespace'inde, CRD'leriyle, bellek limitleri verilmiş |
| `helm_release.cluster_issuers` | Repodaki `helm/cluster-issuers` chart'ı: issuer'lar |

Neden issuer'lar ayrı bir Helm chart'ı ve `kubernetes_manifest` değil: `kubernetes_manifest` plan aşamasında API'ye sorup CRD'nin şemasını istiyor. ClusterIssuer CRD'si aynı apply içinde cert-manager ile kurulduğu için plan aşamasında henüz yok ve plan kırılıyor. Helm böyle bir doğrulama yapmadığı için `depends_on` ile sıralamak yeterli oluyor.

cert-manager'a resource request/limit verildi (`128Mi` limit, cainjector'a `192Mi`): CLAUDE.md'deki 2 vCPU / 4 GB kuralı lokalde de geçerli olsun, ileride buluta taşınırsa aynı değerler çalışsın.

```bash
cd infra/terraform/local
terraform init
terraform plan     # 4 kaynak
terraform apply
```

`terraform output`: namespace listesi, kurulu cert-manager sürümü ve Ingress'lerin kullanacağı issuer adı (`kesinti-ca`).

## Sertifikalar

Let's Encrypt lokalde çalışmıyor: HTTP-01 doğrulaması dışarıdan erişilebilir gerçek bir alan adı istiyor. Onun yerine kendi CA'mızı üretiyoruz. `helm/cluster-issuers` üç adımdan oluşuyor:

1. `selfsigned-bootstrap` (ClusterIssuer): kendini imzalayan issuer.
2. `kesinti-ca` (Certificate, cert-manager namespace'inde): `isCA: true`, bir yıl geçerli, ECDSA P-256. Sonuç `kesinti-ca-tls` secret'ında.
3. `kesinti-ca` (ClusterIssuer): o secret'taki CA ile imzalar. Ingress'ler bunu kullanacak.

Chart iki modlu: `letsencrypt.enabled=true` yapıldığında ACME issuer'ı (staging adresi varsayılan) üretiliyor ve e-posta zorunlu hale geliyor. Yani buluta geçişte chart değişmiyor, sadece values değişiyor.

Doğrulama (2026-10-01):

```
kubectl get clusterissuers
NAME                   READY   AGE
kesinti-ca             True    52s     # mesaj: Signing CA verified
selfsigned-bootstrap   True    52s
```

`int.kesinti.localhost` için deneme amaçlı bir Certificate açtım, 2 saniyede hazır oldu ve içeriği beklendiği gibi çıktı:

```
issuer=CN=Kesinti Haritasi Lokal CA
notBefore=Oct  1 21:00:48 2026 GMT
notAfter=Dec 30 21:00:48 2026 GMT
```

Deneme sertifikası sonra silindi; gerçek Ingress sertifikaları Faz 7'de servislerle birlikte gelecek. Tarayıcı bu CA'yı tanımadığı için uyarı verecek, bu beklenen; sertifika zincirinin kendisi gerçek ve `openssl` ile gösterilebiliyor.

## Adresler

`kesinti.localhost` (prod) ve `int.kesinti.localhost` (int). Windows bu adları kendisi 127.0.0.1/::1'e çözüyor, hosts dosyasına satır eklemek gerekmedi:

```
curl http://kesinti.localhost/   ->  404 (::1)
```

404 doğru cevap: Traefik ayakta ama henüz hiç Ingress yok.

## CI

Faz 5'teki dört workflow'un yol filtreleri sadece `services/**`, `frontend/**` ve `docker-compose.yml`'i dinliyor, yani bu fazda yazılan Terraform ve Helm dosyaları hiçbir koşuya girmiyordu. Beşinci workflow eklendi: `.github/workflows/infra.yml`, `infra/**` ve `helm/**` değişince çalışıyor.

İki job var. `terraform`: `terraform fmt -check -recursive`, sonra `.tf` dosyası olan her dizinde `terraform init -backend=false` ve `validate`. Kümeye bağlanmıyor, bu yüzden runner'da kube bağlantısı gerekmiyor. `helm`: her chart için `helm lint`, `cluster-issuers`'ı iki modda da render etme ve `letsencrypt.enabled=true` iken e-posta zorunluluğunun gerçekten kırdığının kontrolü (render başarılı olursa job başarısız sayılıyor). Son adım `infra/k3d/cluster.yaml`'ın geçerli YAML olduğunu doğruluyor.

Terraform ve Helm, GitHub runner imajında kurulu geldiği için ek action kullanılmadı; tek action `actions/checkout`, o da diğer workflow'lardaki gibi commit SHA'sıyla sabit.

## Kaynak kullanımı

Küme + compose yığını birlikte 2,3 GB RAM kullanıyor (WSL'e verilen 7,6 GB'ın içinde). Faz 8'de kube-prometheus-stack gelince en çok yeri o alacak, zaten küçültülmüş değerlerle kurulacak.

## Takıldığım yerler

- `sudo` bu makinede şifre istiyor, o yüzden k3d'yi `/usr/local/bin` yerine `~/.local/bin` altına kurdum. Makefile `PATH`'e o dizini kendisi ekliyor; terminalden `k3d` çalıştırmak istersen oturum açılışında `~/.local/bin` zaten PATH'e giriyor.
- k3d v5.9.0 release'inde binary'nin yanında ayrı bir sha256 dosyası yayınlanmamış. İndirme resmi GitHub release adresinden HTTPS ile yapıldı, indirilen dosyanın sha256'sı faz notuna yazıldı: `06d8f25bc3a971c4eb29e0ff08429b180402db0f4dec838c9eac427e296800a0` (24.887.458 bayt).
- cert-manager sürümünü önce `v1.19.2` yazmıştım, `helm search repo` güncel sürümün `v1.21.2` olduğunu gösterdi; varsayılan güncellendi.
- Traefik k3s içinde bir Helm job'ıyla kuruluyor ve ilk kurulumda imajları çekerken job üç kez yeniden denedi (`helm-install-traefik` RESTARTS 3). Sonunda Completed oldu.
- Kümeyi silip `make cluster-up` ile sıfırdan kurma denemesinde (4 kaynak 70 saniyede yeniden oluştu, Terraform state eski kalmasına rağmen sorun çıkmadı) komut bittiği anda `curl http://localhost/` boşa düştü: Traefik'in kurulum job'ı hâlâ çalışıyordu. Makefile'a `wait-traefik` adımı eklendi, `cluster-up` artık Traefik rollout'unu bekliyor; sonrasında 80 ve 443 doğrudan 404 veriyor.

## Buluta taşımak istenirse

Bu fazda yazılanların hepsi kalır. Eklenecekler:

1. `infra/terraform/hetzner`: hcloud provider, CX23 sunucu, firewall (22 sadece senin IP'n, 80/443 açık), SSH key, cloud-init ile k3s. Kubeconfig'i lokale alma adımı.
2. `helm/cluster-issuers` values: `letsencrypt.enabled=true`, e-posta, önce staging sonra prod ACME adresi.
3. Gerçek alan adı ve DNS A kaydı.

Faz 7-9 (Helm chart'ları, Argo CD, izleme, k6) her iki durumda da aynı kümede aynı şekilde çalışıyor.
