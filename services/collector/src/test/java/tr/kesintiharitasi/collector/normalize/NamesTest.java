package tr.kesintiharitasi.collector.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void turkceBuyukHarfVeBosluk() {
        assertThat(Names.name("  istanbul   Avrupa ")).isEqualTo("İSTANBUL AVRUPA");
        assertThat(Names.name("ısparta")).isEqualTo("ISPARTA");
        assertThat(Names.name("Çiğli")).isEqualTo("ÇİĞLİ");
        assertThat(Names.name(" ")).isNull();
        assertThat(Names.name(null)).isNull();
    }

    @Test
    void mahalleEkleriAtilir() {
        assertThat(Names.mahalle("Burgazada Mah")).isEqualTo("BURGAZADA");
        assertThat(Names.mahalle("SOLAKLAR MAH.")).isEqualTo("SOLAKLAR");
        assertThat(Names.mahalle("Işıktepe Mahallesi")).isEqualTo("IŞIKTEPE");
        assertThat(Names.mahalle("GÜZELOBA MH.")).isEqualTo("GÜZELOBA");
        assertThat(Names.mahalle("MAHMUTBEY")).isEqualTo("MAHMUTBEY");
        assertThat(Names.mahalle("KARAMAH")).isEqualTo("KARAMAH");
        assertThat(Names.mahalle("-")).isNull();
        assertThat(Names.mahalle("MAH.")).isNull();
    }

    @Test
    void mahalleListesiTekrarsizVeSiraKorunur() {
        assertThat(Names.mahalleler(List.of("Maden Mah", " NİZAM MAH", "maden mah", " ")))
                .containsExactly("MADEN", "NİZAM");
    }

    @Test
    void anahtarTurkceKarakterleriKatlar() {
        assertThat(Names.key("Bitiş Tarihi")).isEqualTo("BITIS TARIHI");
        assertThat(Names.key("ARIZA BİTİS TARİHİ")).isEqualTo(Names.key("Arıza Bitis Tarihi"));
        assertThat(Names.key("YUSUFELİ")).isEqualTo(Names.key("YUSUFELI"));
        assertThat(Names.key("İş Adı")).isEqualTo("IS ADI");
    }
}
