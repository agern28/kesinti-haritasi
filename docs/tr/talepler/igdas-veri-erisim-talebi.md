# Taslak: İGDAŞ'a veri erişim ve izin talebi

English: [../../en/requests/igdas-data-access-request.md](../../en/requests/igdas-data-access-request.md)

Durum: taslak, gönderilmedi. Köşeli parantezli yerleri doldurup gönder.

Nereye: İGDAŞ'ın kurumsal iletişim kanalı (web sitesindeki iletişim formu ya da kurumsal e-posta). İGDAŞ bir İBB şirketi olduğu için kopyasının İBB Açık Veri Portalı ekibine gönderilmesi de işe yarayabilir. Kanalı göndermeden önce İGDAŞ'ın sitesinden kontrol et.

Neden gerekiyor: İGDAŞ'ın iki alan adındaki robots.txt dosyası da (`igdas.istanbul`, `igdas.com.tr`) bütün otomatik erişimi yasaklıyor (`Disallow: /`). Bu yüzden kesinti bilgisini izin almadan okumuyoruz.

---

**Konu:** Doğal gaz kesinti bilgisine otomatik erişim izni talebi

Sayın İGDAŞ Yetkilileri,

Ben [Ad Soyad], [okul / staj yeri] bünyesinde DevOps stajı kapsamında "Kesinti Haritası" adlı açık kaynak ve ticari olmayan bir proje geliştiriyorum (https://github.com/agern28/kesinti-haritasi). Proje, resmi kaynaklarda yayınlanan elektrik, su ve doğal gaz kesintilerini tek bir haritada gösteriyor. Her kaydın yanında kaynak kurumun adı ve orijinal duyurunun linki yer alıyor.

Haritada İstanbul'daki planlı ve arıza kaynaklı doğal gaz kesintilerini de göstermek istiyoruz. Web sitenizin robots.txt dosyası otomatik erişime izin vermediği için sitenizden veri almıyoruz ve bu konuda sizden izin istiyoruz.

Talebimiz aşağıdakilerden biri:

1. Kesinti bilgisinin yayınlandığı sayfaya ya da servise, aşağıdaki kurallarla otomatik erişim izni verilmesi,
2. planlı ve arıza kaynaklı kesintilerin (ilçe, mahalle, başlangıç ve tahmini bitiş zamanı) bir API ya da İBB Açık Veri Portalı'nda düzenli güncellenen bir veri seti olarak paylaşılması.

Veriyi şöyle kullanacağız:

- Planlı kesinti bilgisi en fazla 15 dakikada, arıza bilgisi en fazla 5 dakikada bir alınır. İsteklerde proje adı ve iletişim adresi bulunan bir User-Agent kullanılır.
- Veri değiştirilmeden gösterilir. Kaynak olarak İGDAŞ ve orijinal duyurunun linki verilir.
- Kişisel veri ya da abone bilgisi işlenmez.
- İstediğiniz zaman erişimi durdurabilirsiniz. Talep etmeniz halinde verinin kullanımını hemen bırakırız.

Bilgilerinize sunar, cevabınızı rica ederim.

Saygılarımla,

[Ad Soyad]
[E-posta]
[Telefon, isteğe bağlı]
[Tarih]
