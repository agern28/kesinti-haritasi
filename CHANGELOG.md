# Değişiklikler

Biçim [Keep a Changelog](https://keepachangelog.com/tr-TR/1.1.0/), sürümler [Semantic Versioning](https://semver.org/lang/tr/). Uygulamadaki "Yenilikler" penceresi bu dosyadan okunuyor. GitHub Release notları da buradan, sürümün bölümünden alınıyor. İngilizcesi: [CHANGELOG.en.md](CHANGELOG.en.md).

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
