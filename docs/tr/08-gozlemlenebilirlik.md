# Faz 8 - Gözlemlenebilirlik

Faz 7'de uygulama kümede çalışıyordu ama bir şeyin bozulduğunu ancak elle bakınca anlıyorduk. Bu fazda Prometheus, Alertmanager ve Grafana kuruldu; kaynak taramaları, uygulama ve küme için panolar yazıldı; taramalar durduğunda haber veren alarmlar tanımlandı.

| Ne | Adres |
|---|---|
| Grafana | https://grafana.localhost (`admin`, parola: `make grafana-password`) |
| Prometheus | `make prometheus` → http://localhost:9090 |
| Alertmanager | `make alertmanager` → http://localhost:9093 |
| Alarm durumu | `make alerts` |

## Kurulum neden ikiye bölündü

kube-prometheus-stack'i **Terraform** kuruyor, bizim objelerimizi (ServiceMonitor, alarm kuralları, panolar) **Argo CD** kuruyor.

Sebep: Alertmanager'ın Telegram ayarı bot token'ı ve chat id'yi istiyor. İkisi de repoya giremez (CLAUDE.md kuralı), `terraform.tfvars`'ta duruyorlar ve `.gitignore`'da. Alertmanager'ın yönlendirmesi de bu yüzden Terraform tarafında.

Önce `AlertmanagerConfig` CRD'siyle denedim: token'ı secret'tan okuyabiliyor ama `chatID` alanı sayı bekliyor ve secret'tan okunamıyor. Yani chat id'yi repoya yazmak gerekirdi. Bunun yerine Alertmanager'ın kendi `config` bloğu kullanıldı: token dosya olarak mount ediliyor (`bot_token_file`), chat id tfvars'tan geliyor.

Repoya girebilecek her şey repoda: `helm/monitoring` chart'ı ve `gitops/apps/monitoring.yaml`.

## 4 GB'a sığdırma

k3s'te olmayan bileşenlerin izlenmesi kapatıldı: etcd, kube-controller-manager, kube-scheduler, kube-proxy. Açık bırakılsalardı sürekli "down" görünür ve kendi alarmlarını üretirlerdi.

Prometheus 2 gün veri tutuyor, 3 GiB disk sınırıyla; tarama aralığı 30 saniye. Her bileşene bellek limiti verildi: Prometheus 1 GiB, Grafana 384 MiB, Alertmanager 128 MiB, kube-state-metrics 128 MiB, node-exporter 64 MiB, operatör 192 MiB.

Ölçülen: izleme yığını kurulduktan sonra WSL'de toplam kullanım 5,0 GB (küme + uygulamalar + izleme). Compose yığını bu noktada kapatıldı; ikisi birden açıkken 4 GB'lık bir makinede yer kalmıyor.

## Ne toplanıyor

`helm/monitoring` iki ServiceMonitor kuruyor, ikisi de `monitoring` namespace'inde durup `namespaceSelector` ile iki ortamdaki servisleri topluyor. Ortam başına ayrı chart kurmak gerekmiyor.

Prometheus'un hedefleri (`make prometheus` → Targets): dört tane, hepsi `up`.

```
kesinti-prod  collector   up
kesinti-prod  api         up
kesinti-int   collector   up
kesinti-int   api         up
```

Küçük bir ayrıntı: `job` etiketi ServiceMonitor'ın adından değil, **Service'in adından** geliyor. Yani kurallarda `job="api"` yazılıyor, `job="kesinti-api"` değil. İlk yazdığım kurallar bu yüzden hiçbir seriyle eşleşmiyordu.

Servislere metrik eklemek gerekmedi: Faz 3'te eklenen metrikler bu fazın istediği her şeyi veriyor.

| Metrik | Ne işe yarıyor |
|---|---|
| `collector_last_success_timestamp{source,feed}` | Alarmların tamamı bunun üstüne kurulu |
| `collector_items_last_scan`, `collector_items_total` | Kaynak ne kadar kayıt döndürüyor |
| `collector_errors_total`, `collector_scan_duration_seconds` | Hata ve süre |
| `collector_events_total{event}` | NEW / UPDATED / GONE |
| `api_sse_clients` | Haritayı açık tutan tarayıcı sayısı |
| `api_sse_delivery_seconds` | Olayın veritabanından tarayıcıya ulaşma süresi (histogram) |
| `api_summary_cache_total{result}` | Özet cache isabet oranı |
| `api_stream_events_total{event,result}` | Stream'den işlenen olaylar |

## Alarmlar

| Alarm | Eşik | Neden |
|---|---|---|
| `KesintiArizaTaramasiDurdu` | 30 dakika | Arıza sayfaları 5 dakikada bir taranıyor |
| `KesintiPlanliTaramasiDurdu` | 3 saat | Planlı duyurular 15 dakikada bir taranıyor |
| `KesintiAcikVeriTaramasiDurdu` | 26 saat | İBB veri seti günde bir taranıyor (2026-09-11 kararı) |
| `KesintiTaramaHatasiArtiyor` | 15 dakikada 3 hata | Eşiğe varmadan önce görmek için |
| `KesintiServisAyaktaDegil` | 5 dakika | Prometheus servisten metrik alamıyor |
| `KesintiSseGecikmesiYuksek` | p95 > 5 sn | Canlı harita gecikiyor demektir |
| `KesintiApiHataOrani` | %5 5xx | api hata veriyor |

**Alarmlar sadece PROD'a bakıyor.** INT'te tarama kapalı olduğu için `collector_last_success_timestamp` orada hep 0 kalıyor ve `time() - 0` ifadesi 29 milyon dakika gibi bir değer veriyor. Kurallara `namespace="kesinti-prod"` filtresi konmasaydı INT yüzünden sürekli alarm çalardı.

Bir de şunu kurarken fark ettim: panodaki "en eski tarama" göstergesi başta bütün feed'leri kapsıyordu ve `ISKI/daily` yüzünden 9 saat gösteriyordu. O kaynak günde bir taranıyor, yani normal. Gösterge ikiye ayrıldı: arıza ve planlı feed'ler için dakika cinsinden bir gösterge, günlük açık veri kaynağı için saat cinsinden ayrı bir gösterge (eşik 26 saat).

## Panolar

Üç pano repoda JSON olarak duruyor (`helm/monitoring/dashboards/`) ve ConfigMap olarak kuruluyor; Grafana'nın sidecar'ı `grafana_dashboard` etiketli ConfigMap'leri bulup yüklüyor. Yani Grafana arayüzünden elle pano eklemeye gerek yok ve panolar sürüm kontrolünde.

- **Kaynak sağlığı**: en eski tarama (arıza/planlı), açık veri kaynağının son taraması, son 1 saatteki hata sayısı, son taramalardaki toplam kayıt, feed başına "son taramadan bu yana geçen süre" (her tarama bu çizgiyi sıfırlar; düz yukarı giden çizgi tarama durdu demektir), feed başına kayıt sayısı, NEW/UPDATED/GONE olayları, ortalama tarama süresi.
- **Uygulama**: SSE bağlı istemci sayısı, olayın tarayıcıya ulaşma süresi (p50/p95), özet cache isabet oranı, istek hızı, duruma göre istekler, yola göre ortalama yanıt süresi, stream'den işlenen olaylar.
- **Küme**: çalışan pod sayısı, son 1 saatteki yeniden başlatmalar, düğüm bellek kullanımı, HPA'nın şu anki replika sayısı, pod başına CPU ve bellek, bellek limitine yakınlık, HPA'nın şu anki ve hedef replikaları.

Arayüzden yapılan değişiklikler kalıcı değil: sidecar dosyayı yeniden yüklediği an geri döner. Değişiklik repodaki JSON'a yazılmalı.

İstek gecikmesinde p95 yok, ortalama var: Spring Boot `http_server_requests` için histogram kovası yayınlamıyor. SSE teslim süresinde kovalar var, orada gerçek p95 hesaplanıyor.

## Alarm testi

Bir kaynağı geçici olarak bozmanın en temiz yolu collector'ı durdurmak:

```bash
make alarm-testi        # PROD collector'i durdurur
make alerts             # durumu izler
make alarm-testi-bitir  # geri acar
```

`make alarm-testi` iki şey yapıyor: deployment'ı sıfıra indiriyor ve Argo CD'nin `selfHeal` ayarını kapatıyor. İkincisi şart, yoksa Argo CD birkaç saniye içinde pod'u geri açıyor ve test hiç başlamıyor.

Beklenen akış: tarama durduktan 30 dakika sonra kural eşiği aşıyor, `for: 5m` yüzünden önce `pending`, sonra `firing` oluyor. Telegram kuruluysa mesaj düşüyor. `make alarm-testi-bitir` sonrası ilk başarılı taramayla alarm kendiliğinden kapanıyor ve "COZULDU" mesajı gidiyor.

Gerçekten koşturdum, zaman çizgisi (2026-10-02, UTC):

| Saat | Ne oldu |
|---|---|
| 07:24:57 | NetworkPolicy uygulandı, collector'ın internet çıkışı kesildi |
| 07:27:37 | İlk başarısız taramalar metriklerde göründü (`java.net.ConnectException`) |
| 07:42:14 | `KesintiTaramaHatasiArtiyor` aktif oldu |
| 07:47:39 | Aynı alarm `firing`, Alertmanager'da dört feed için aktif |
| 07:51:44 | Gecikme 30 dakikayı geçti, `KesintiArizaTaramasiDurdu` aktif |
| 07:57:40 | O alarm da `firing` |
| 07:58:42 | NetworkPolicy silindi (`make alarm-testi-bitir`) |
| 08:05:23 | En son düzelen feed: AEDAŞ arıza taraması başarıyla bitti |
| 08:05:48 | `KesintiArizaTaramasiDurdu` kendiliğinden `inactive` |

Düzelmenin 7 dakika sürmesinin sebebi tarama aralığı ve bir ayrıntı: AEDAŞ'ın arıza taraması 208 saniye sürüyor. CK Enerji arıza kayıtlarında konum trafo numarasından ayrı isteklerle bulunuyor (tarama başına en fazla 40 istek) ve host başına en az 2 saniye bekliyoruz. Yani bu feed'in kendi süresi 3,5 dakika; 5 dakikalık aralıkla arasında pek pay yok. İzlenmesi gereken bir şey: kaynak yavaşlarsa tarama kendi takvimine yetişemez.

`KesintiTaramaHatasiArtiyor` düzelmeden sonra da bir süre açık kalıyor, çünkü 30 dakikalık pencerede hâlâ hata sayıyor. Beklenen davranış.

Bu testte çalışmayan alarmı da not etmek lazım: `KesintiServisAyaktaDegil` kuralı `up == 0` arıyor, ama deployment sıfıra indirildiğinde hedef servis keşfinden tamamen çıkıyor ve sıfır olacak bir `up` serisi kalmıyor. O alarm "pod ayakta ama metrik verilemiyor" durumunu yakalıyor; "pod hiç yok" durumunu tarama gecikmesi alarmları yakalıyor.

## Telegram

Token ve chat id `terraform.tfvars`'a yazılır, repoya girmez:

```hcl
telegram_bot_token = "123456:ABC..."
telegram_chat_id   = "-1001234567890"
```

Sonra `make bootstrap` (ya da `terraform apply`). İkisi boşken Alertmanager alarmları "bos" alıcıya gönderiyor: alarmlar Alertmanager arayüzünde ve `make alerts` çıktısında görünüyor, hiçbir yere iletilmiyor. Yani izleme Telegram olmadan da çalışıyor, sadece bildirim gelmiyor.

## Takıldığım yerler

- **Grafana açılamadı.** 256 MiB bellek limiti ve chart'ın varsayılan liveness probe'u (60 saniye) ile Grafana 12 açılışını tamamlayamadan öldürülüyordu; pod 2/3 kalıyor ve Ingress 503 veriyordu. Limit 384 MiB'e, liveness başlangıç gecikmesi 120 saniyeye çekildi. Grafana bu makinede açılmak için ~90 saniye istiyor.
- **Terraform state kilidi.** Helm, Grafana sağlıklı olsun diye beklerken (`wait = true`) WSL bir süre yanıt vermedi ve komut yarıda kesildi; arkada `terraform apply` çalışmaya devam ettiği için ikinci apply "Error acquiring the state lock" dedi. Çözüm kilidi zorla açmak değil: çalışan süreci kontrol et (`pgrep -a terraform`), gerçekten çalışıyorsa bekle. Burada Grafana'yı `kubectl patch` ile sağlıklı hale getirmek bekleyen apply'ın tamamlanmasını sağladı.
- **Prometheus container'ında `wget` ve `curl` yok.** Hedefleri ve kuralları API'den okumak için `kubectl port-forward` gerekti; `scripts/alerts.sh` de bu yüzden port-forward kullanıyor.
- **`job` etiketi Service adından geliyor** (yukarıda).
- **`AlertmanagerConfig`'in `chatID` alanı secret'tan okunamıyor** (yukarıda).
