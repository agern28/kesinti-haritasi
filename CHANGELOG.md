# Değişiklikler

Biçim [Keep a Changelog](https://keepachangelog.com/tr-TR/1.1.0/), sürümler [Semantic Versioning](https://semver.org/lang/tr/). Uygulamadaki "Yenilikler" penceresi bu dosyadan okunuyor. GitHub Release notları da buradan, sürümün bölümünden alınıyor. İngilizcesi: [CHANGELOG.en.md](CHANGELOG.en.md).

## [1.0.1] - 2026-10-02

Uygulamanın görünen davranışı aynı; bu sürüm dayanıklılık ve işletme tarafı. Kubernetes'e kurulan imajların 1.0.0'dan sonraki düzeltmeleri içermesi için çıkarıldı.

### Düzeltildi
- Kaynak sitenin robots.txt dosyasına geçici olarak ulaşılamadığında bütün kaynaklar "izin verilmiyor" sayılıyordu. Artık elde geçerli bir kopya varsa o kullanılıyor, log da sebebi doğru yazıyor.
- Kubernetes'te arayüzden api'ye giden istekler 502 veriyordu: nginx'in isim çözücüsü kısa servis adını çözemiyor, tam alan adı gerekiyordu.
- İlçe sınırları dosyasının adresi sürümlü: yeni sınır dosyası çıkınca tarayıcı bir hafta eski dosyada kalmıyor.

### Değişti
- Çok derin sayfa isteği (`page * size` 50.000'den büyük) artık 400 dönüyor; dışa açık api'de ucuz bir yük bindirme yoluydu.
- Aktif kesinti listesi ve harita özeti için iki veritabanı indeksi eklendi.
- Redis olay akışının uzunluğu ölçüme göre 100.000'den 30.000'e indirildi (olay başına ~0,56 KB).
- Bağımlılıklar güncellendi; Dependabot artık ana sürüm atlamalarını önermiyor.

## [1.0.0] - 2026-09-15

### Eklendi
- Türkiye ilçe haritası. İlçeler aktif kesinti sayısına ve türüne göre renkleniyor.
- Tür filtresi: elektrik ve su. Doğalgaz kaynağı eklenince filtre açılacak.
- İlçeye tıklayınca o ilçedeki aktif ve yaklaşan kesintiler: mahalleler, saatler, kaynak ve duyuru linki.
- Canlı güncelleme: yeni kesinti sayfa yenilenmeden haritaya düşüyor, ilçe kısa bir süre yanıp sönüyor.
- Veri tazeliği: son güncelleme zamanı ve kaynak bazında son tarama. Gecikmiş kaynak varsa uyarı çıkıyor.
- Bağlantı durumu: canlı ya da yeniden bağlanıyor.
- Kaynaklar: BEDAŞ, AEDAŞ, ÇEDAŞ, KCETAŞ (elektrik), İZSU (su) ve İBB Açık Veri'den İSKİ geçmiş verisi.
- İZSU'nun arıza kayıtları: İzmir'deki su arızaları da haritada.

### Düzeltildi
- KCETAŞ'ın Sivas'taki Gemerek kesintileri Kayseri'ye yazılıyordu, artık Sivas'ta.
- İZSU taraması bakım ya da arıza olmayan saatlerde (gece) hata veriyordu.
- ÇEDAŞ'ın sunucularından biri 2024'ten kalma liste döndürüyordu; Sivas, Tokat ve Yozgat'taki kesintiler haritadan düşüp geri geliyordu. Eski liste artık kabul edilmiyor.
- Frontend, api yeniden başlatılınca bağlantısını kaybediyordu.
