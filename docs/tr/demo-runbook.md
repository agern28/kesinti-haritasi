# Demo runbook

Projeyi 15-20 dakikada baştan sona göstermek için komut komut akış. Her adımda ne çalıştırılacağı, ne görüneceği ve ne anlatılacağı yazıyor. Ortam: WSL içinde Docker, lokal k3s kümesi (Faz 6-8).

İngilizcesi: [../en/demo-runbook.md](../en/demo-runbook.md).

## 0. Hazırlık (demodan önce, 10 dakika)

```bash
cd ~/projects/kesinti-haritasi
make cluster-up      # kume kapaliysa: k3d + Terraform (cert-manager, Argo CD, izleme)
make cluster-check   # her sey yesil mi
```

Kontrol listesi:
- `make cluster-check` hepsi "ok" diyor.
- Tarayıcıda dört sekme hazır: https://kesinti.localhost, https://int.kesinti.localhost, https://argocd.localhost, https://grafana.localhost
- Argo CD ve Grafana parolaları elinizin altında: `make argocd-password`, `make grafana-password`
- Sertifika uyarısını önceden geçin (kendi CA'mız), demoda vakit kaybetmeyin.
- PROD veritabanında veri olsun: `curl -sk https://kesinti.localhost/api/map/summary | head -c 200`

Compose yığınını kapalı tutun (`make stop`): aynı anda tek collector kaynak sitelere gitsin ve bellek yeterli olsun.

## 1. Uygulama (3 dakika)

Tarayıcıda https://kesinti.localhost.

- Harita açılır, ilçeler aktif kesinti sayısına göre renklenir.
- Bir ilçeye tıklayın: o ilçedeki aktif ve yaklaşan kesintiler, mahalleler, saatler, kaynak ve duyuru linki.
- Sağ üstte veri tazeliği: hangi kaynak ne zaman tarandı.
- Sol altta bağlantı durumu "Canlı" ve sürüm etiketi `v1.0.1 · PROD`.

Anlatılacak: veri resmi kaynaklardan toplanıyor, kesinti veritabanına düştüğü an SSE ile tarayıcıya gidiyor, sayfa yenilenmiyor.

Canlı güncellemeyi göstermek için (isteğe bağlı): ikinci bir sekmede aynı sayfayı açın; collector yeni bir kesinti bulduğunda ilçe kısa süre yanıp söner. Tarama 5 dakikada bir olduğu için demo sırasında denk gelmeyebilir; `make logs-collector` ile son taramaları göstermek daha güvenli.

## 2. İki ortam (2 dakika)

```bash
curl -sk https://kesinti.localhost/env.json      # {"environment":"PROD"}
curl -sk https://int.kesinti.localhost/env.json  # {"environment":"INT"}
```

INT'te harita boş: orada kaynak taraması kapalı, çünkü bir kaynağa aynı anda tek yerden gidilir.

Farkların nereden geldiğini gösterin:

```bash
cat gitops/prod/collector.yaml   # scheduling.enabled: true
cat gitops/int/collector.yaml    # scheduling.enabled: false
```

Aynı imaj iki ortamda çalışıyor; ortam etiketi bile çalışma anında (`APP_ENV`) geliyor.

## 3. GitOps (3 dakika)

https://argocd.localhost → dokuz Application, hepsi Synced/Healthy. Ağaçta `prod-api`'yi açıp Deployment, Service, HPA'yı gösterin.

```bash
make argocd-apps
```

Anlatılacak: kümede elle `kubectl apply` yapılmıyor. Kök Application repodaki `gitops/apps` dizinini izliyor; yeni servis ya da ortam eklemek repoya dosya eklemek demek. `selfHeal` açık olduğu için kümede elle yapılan değişiklik geri alınıyor — bunu istersen canlı gösterebilirsin:

```bash
kubectl -n kesinti-prod scale deploy/frontend --replicas=2   # elle degistir
kubectl -n kesinti-prod get deploy frontend -w               # Argo CD birkac saniyede 1'e cekiyor
```

## 4. Sürüm hattı (3 dakika)

Anlatılacak akış (gerçek bir tag atmak demoda uzun sürer, 1.0.1'in geçmişini gösterin):

1. `git tag api-v1.1.0 && git push origin api-v1.1.0`
2. CI imajı derler, Trivy ile tarar, GHCR'a gönderir, CHANGELOG'dan notunu alan bir Release açar.
3. `promote-int` workflow'u `gitops/int/api.yaml`'daki etiketi günceller ve `main`'e commit eder.
4. Argo CD o commit'i görüp INT'e kurar.
5. `promote-prod` workflow'u elle tetiklenir, INT'teki sürümü PROD'a taşıyan PR'ı açar; PR birleşince Argo CD PROD'u geçirir.

Gösterilecek yerler: GitHub Actions'ta `collector/api/frontend` ve `promote-int` koşuları, `git log --oneline` içindeki `chore(gitops): promote ...` commit'leri, GHCR'daki imaj etiketleri.

## 5. İzleme (3 dakika)

https://grafana.localhost → "Kesinti Haritası" klasörü.

- **Kaynak sağlığı**: her feed için son başarılı taramadan bu yana geçen süre. Testere dişi normal; düz yukarı giden çizgi tarama durdu demek.
- **Uygulama**: SSE bağlı istemci sayısı ve olayın veritabanından tarayıcıya ulaşma süresi (p50/p95), özet cache isabet oranı.
- **Küme**: pod CPU/bellek, bellek limitine yakınlık, HPA replikaları.

Canlı SSE göstergesini kanıtlamak için haritayı bir sekmede açık bırakın; "SSE bağlı istemci" 1 olur, sekmeyi kapatınca ~30 saniyede 0'a döner.

## 6. Alarm denemesi (2 dakika anlatım + arka planda bekleme)

```bash
make alarm-testi     # PROD collector'in internet cikisini keser
make alerts          # birkac dakika sonra: pending -> ALARM
make alarm-testi-bitir
```

Zamanlama: tarama hataları ~15-20 dakikada alarma dönüyor, "tarama durdu" alarmı 30 dakika + 5 dakika sonra. Demo kısaysa testi demodan önce başlatıp alarmı hazır gösterin, sonra `make alarm-testi-bitir` ile çözülmesini izleyin.

Anlatılacak: eşikler kaynağın tarama sıklığına göre (arıza 30 dakika, planlı 3 saat, günlük açık veri 26 saat). Telegram token'ı girilirse aynı alarm telefona düşüyor.

## 7. Yük testi ve ölçeklenme (3 dakika)

```bash
make loadtest        # yaklasik 5 dakika; normal yuk, 10 kat sicrama, dusus
```

Çıktıda: k6 özetinde istek/sn, p95, hata oranı; sonra HPA'nın replika değişimi (hangi saniyede kaç replikaya çıktı). Grafana'nın küme panosunda aynı anda replika ve CPU grafiğini gösterin.

Ölçülen değerler: [09-v11-ve-dayaniklilik.md](09-v11-ve-dayaniklilik.md).

## 8. Geri alma (2 dakika)

```bash
# PROD api'yi bir onceki surume al
SURUM=1.0.0 yq -i '.image.tag = strenv(SURUM)' gitops/prod/api.yaml
git commit -am "chore(gitops): roll PROD api back to 1.0.0" && git push
make argocd-refresh
```

Gözle doğrulanabilir fark: 1.0.1'de derin sayfa isteği 400 dönüyor, 1.0.0'da 200.

```bash
curl -sk -o /dev/null -w '%{http_code}\n' 'https://kesinti.localhost/api/outages?page=2000&size=100'
```

Sonra aynı yolla 1.0.1'e geri dönün. Anlatılacak: geri alma da ileri alma gibi bir commit; kümeye elle dokunulmuyor, geçmiş git'te duruyor.

## 9. Kapanış

```bash
make cluster-status   # son durum
```

Demo bittikten sonra bırakılacak hal:
- Alarm denemesi yapıldıysa `make alarm-testi-bitir` çalıştırıldı mı?
- Geri alma denemesi yapıldıysa sürüm 1.0.1'e döndü mü?
- Küme açık kalacaksa sorun yok; kapatmak için `make cluster-down` (uygulama verisi compose'un volume'lerinde kalır).

## Sık çıkan sorular

**Neden `*.localhost`?** Lokal kümede gerçek alan adı ve Let's Encrypt yok; Traefik bu adlarda dinliyor, sertifikalar kümedeki kendi CA'mızdan. Buluta taşımak için gereken fark [06-altyapi.md](06-altyapi.md) sonunda.

**Tarayıcı neden uyarıyor?** Sertifikayı kendi CA'mız imzaladı, tarayıcı onu tanımıyor. Zincir gerçek: `openssl s_client -connect 127.0.0.1:443 -servername kesinti.localhost`.

**INT'te neden veri yok?** Kaynak sitelere nazik olmak için aynı anda tek collector tarıyor, o da PROD'daki.

**Kaç kaynak var?** Altı: BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ (elektrik), İZSU (su, canlı) ve İBB Açık Veri'den İSKİ (geçmiş veri). Doğalgaz hâlâ yok; sebebi [01-kesif-ve-iskelet.md](01-kesif-ve-iskelet.md) ve [09-v11-ve-dayaniklilik.md](09-v11-ve-dayaniklilik.md).
