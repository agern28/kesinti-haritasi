package tr.kesintiharitasi.collector.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageKeys;
import tr.kesintiharitasi.collector.model.OutageType;

/**
 * Ilce adlarinin sinir verisine uydurulmasi. Vakalar canli veritabanindan cikti
 * (2026-10-04: 19.285 kaydin 174'u haritada hicbir poligona dusmuyordu).
 */
class DistrictsTest {

    private static Outage outage(String il, String ilce, String externalId, String... mahalleler) {
        return new Outage("BEDAS", externalId, OutageType.ELECTRICITY, true, il, ilce, List.of(mahalleler),
                Instant.parse("2026-10-04T09:00:00Z"), Instant.parse("2026-10-04T17:00:00Z"),
                null, "https://ornek", null, null);
    }

    @Test
    @DisplayName("sinir verisiyle eslesen ad oldugu gibi kalir")
    void eslesenAdDegismez() {
        List<Outage> out = Districts.fix(List.of(outage("İSTANBUL", "KADIKÖY", "1")));

        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.ilce()).isEqualTo("KADIKÖY");
            assertThat(o.externalId()).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("parantezli ek atilir: SİVAS (MERKEZ) -> SİVAS")
    void parantezAtilir() {
        List<Outage> out = Districts.fix(List.of(outage("SİVAS", "SİVAS (MERKEZ)", "1")));

        assertThat(out).singleElement().extracting(Outage::ilce).isEqualTo("SİVAS");
    }

    @Test
    @DisplayName("il adi oneki atilir: TOKAT MERKEZ -> TOKAT, BURDUR KEMER -> KEMER")
    void ilOnekiAtilir() {
        assertThat(Districts.fix(List.of(outage("TOKAT", "TOKAT MERKEZ", "1"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("TOKAT");
        assertThat(Districts.fix(List.of(outage("BURDUR", "BURDUR KEMER", "2"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("KEMER");
    }

    @Test
    @DisplayName("MERKEZ ve KIRSAL il adina cevrilir")
    void merkezVeKirsal() {
        assertThat(Districts.fix(List.of(outage("BURDUR", "MERKEZ", "1"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("BURDUR");
        assertThat(Districts.fix(List.of(outage("SİVAS", "SİVAS KIRSAL", "2"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("SİVAS");
    }

    @Test
    @DisplayName("birlesik ilce iki kayda bolunur, dedup anahtarlari ayrilir")
    void birlesikIlceBolunur() {
        List<Outage> out = Districts.fix(List.of(outage("ANTALYA", "KONYAALTI / KEPEZ", "42", "LİMAN")));

        assertThat(out).hasSize(2);
        assertThat(out).extracting(Outage::ilce).containsExactly("KONYAALTI", "KEPEZ");
        assertThat(out).allSatisfy(o -> {
            assertThat(o.mahalleler()).containsExactly("LİMAN");
            assertThat(o.startsAt()).isEqualTo(Instant.parse("2026-10-04T09:00:00Z"));
        });
        // Ayni external_id iki kayda da dusseydi ikinci kayit birincinin uzerine yazardi.
        assertThat(out).extracting(OutageKeys::dedupKey).doesNotHaveDuplicates();
        assertThat(out).extracting(Outage::externalId).containsExactly("42#KONYAALTI", "42#KEPEZ");
    }

    @Test
    @DisplayName("external_id yoksa bolunen kayitlarin hash anahtari zaten ayri")
    void externalIdYoksaAnahtarAyri() {
        List<Outage> out = Districts.fix(List.of(outage("ANTALYA", "KONYAALTI / KEPEZ", null, "LİMAN")));

        assertThat(out).hasSize(2).extracting(Outage::externalId).containsOnlyNulls();
        assertThat(out).extracting(OutageKeys::dedupKey).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ilce yerine semt gelmisse ilceye cevrilir, semt mahalle listesine eklenir")
    void semtIlceyeCevrilir() {
        List<Outage> out = Districts.fix(List.of(outage("İSTANBUL", "YENİBOSNA", "1", "HÜRRİYET")));

        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.ilce()).isEqualTo("BAHÇELİEVLER");
            // Bilgi kaybolmuyor: kaynagin yazdigi semt mahalleler listesinin basinda.
            assertThat(o.mahalleler()).containsExactly("YENİBOSNA", "HÜRRİYET");
            assertThat(o.externalId()).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("semt zaten mahalle listesindeyse tekrar eklenmez")
    void semtTekrarEklenmez() {
        List<Outage> out = Districts.fix(List.of(outage("İSTANBUL", "ZİNCİRLİKUYU", "1", "ZİNCİRLİKUYU", "ESENTEPE")));

        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.ilce()).isEqualTo("ŞİŞLİ");
            assertThat(o.mahalleler()).containsExactly("ZİNCİRLİKUYU", "ESENTEPE");
        });
    }

    @Test
    @DisplayName("takma ad yalnizca kendi ilinde gecerli")
    void takmaAdIlBagimli() {
        // Beyazit İstanbul'da Fatih'e dusuyor; baska bir ilde ayni ad oldugu gibi kalir.
        assertThat(Districts.fix(List.of(outage("İSTANBUL", "BEYAZIT", "1"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("FATİH");
        assertThat(Districts.fix(List.of(outage("ANKARA", "BEYAZIT", "2"))))
                .singleElement().extracting(Outage::ilce).isEqualTo("BEYAZIT");
    }

    @Test
    @DisplayName("bos liste ve tek kayit disinda siralama korunur")
    void siraKorunur() {
        List<Outage> out = Districts.fix(List.of(
                outage("İSTANBUL", "KADIKÖY", "1"),
                outage("İSTANBUL", "YENİBOSNA", "2"),
                outage("İSTANBUL", "ÜSKÜDAR", "3")));

        assertThat(out).extracting(Outage::ilce).containsExactly("KADIKÖY", "BAHÇELİEVLER", "ÜSKÜDAR");
        assertThat(Districts.fix(List.of())).isEmpty();
    }
}
