package tr.kesintiharitasi.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void turkceKarakterVeBuyukKucukHarfFarkiYok() {
        assertThat(Names.key("Şişli")).isEqualTo("SISLI");
        assertThat(Names.key("ŞİŞLİ")).isEqualTo("SISLI");
        assertThat(Names.key("sisli")).isEqualTo("SISLI");
        assertThat(Names.key("  Gaziosmanpaşa ")).isEqualTo("GAZIOSMANPASA");
        assertThat(Names.key("ÇİĞLİ")).isEqualTo(Names.key("cigli"));
        assertThat(Names.key("YUSUFELİ")).isEqualTo(Names.key("YUSUFELI"));
        assertThat(Names.key("100. YIL")).isEqualTo("100 YIL");
        assertThat(Names.key(null)).isEmpty();
    }

    @Test
    void turkceOlmayanLocaleIleKucukHarfeCevrilmisAd() {
        // "İ".toLowerCase(Locale.ROOT) = "i" + U+0307 (birlesik nokta)
        String decomposed = "ŞİŞLİ".toLowerCase(Locale.ROOT);
        assertThat(decomposed).hasSize(7);
        assertThat(Names.key(decomposed)).isEqualTo("SISLI");
    }
}
