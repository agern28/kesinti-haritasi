# Taslak: İSKİ'ye veri erişim talebi

English: [../../en/requests/iski-data-access-request.md](../../en/requests/iski-data-access-request.md)

Durum: taslak, gönderilmedi. Köşeli parantezli yerleri doldurup gönder.

Nereye: İSKİ bir kamu kurumu olduğu için 4982 sayılı Bilgi Edinme Hakkı Kanunu kapsamında başvuru yapılabilir (İSKİ'nin bilgi edinme formu ya da CİMER). Kanuna göre cevap süresi 15 iş günü. Başvuru kanalını göndermeden önce İSKİ'nin sitesinden kontrol et. Aynı metin İBB Açık Veri Portalı ekibine de gönderilebilir.

---

**Konu:** Arıza ve kesinti verisine otomatik erişim izni talebi

Sayın İSKİ Genel Müdürlüğü,

Ben [Ad Soyad], [okul / staj yeri] bünyesinde DevOps stajı kapsamında "Kesinti Haritası" adlı açık kaynak ve ticari olmayan bir proje geliştiriyorum (https://github.com/agern28/kesinti-haritasi). Proje, resmi kaynaklarda yayınlanan elektrik ve su kesintilerini tek bir haritada gösteriyor. Her kaydın yanında kaynak kurumun adı ve orijinal duyurunun linki yer alıyor.

İSKİ web sitesinin "Arıza ve Kesintiler" sayfasında yayınlanan bilgilerin bu haritada da görünmesini istiyoruz. Sayfanın veriyi aldığı servis (iskiapi.iski.istanbul) erişim anahtarı istiyor. Sitenin koduna gömülü anahtarı izinsiz kullanmak istemediğimiz için sizden izin ve erişim talep ediyoruz.

Talebimiz aşağıdakilerden biri:

1. Bölgesel arıza/kesinti listesine (`iski/bolgeselAriza/listesi`) projemize özel bir erişim anahtarıyla erişim izni verilmesi,
2. ya da aynı verinin İBB Açık Veri Portalı'nda düzenli olarak (günlük ya da daha sık) güncellenen bir veri seti olarak yayınlanması.

Veriyi şöyle kullanacağız:

- En fazla 5 dakikada bir istek atılır. İsteklerde proje adı ve iletişim adresi bulunan bir User-Agent kullanılır.
- Veri değiştirilmeden gösterilir. Kaynak olarak İSKİ ve orijinal sayfanın linki verilir.
- Kişisel veri işlenmez. Sadece ilçe, mahalle, başlangıç, tahmini bitiş ve açıklama alanları kullanılır.
- İstediğiniz zaman erişimi durdurabilirsiniz. Talep etmeniz halinde verinin kullanımını hemen bırakırız.

Ayrıca İBB Açık Veri Portalı'ndaki "İstanbul'da Meydana Gelen Su Kesintileri" veri setini mahalle bazında geçmiş kesinti istatistiği için kullanmayı planlıyoruz. Veri setinin 2024 sonrası dönem için güncellenmesi mümkünse bu da bizim için çok değerli olur.

Bilgilerinize sunar, cevabınızı rica ederim.

Saygılarımla,

[Ad Soyad]
[E-posta]
[Telefon, isteğe bağlı]
[Tarih]
