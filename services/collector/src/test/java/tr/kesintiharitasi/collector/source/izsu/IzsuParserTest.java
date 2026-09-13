package tr.kesintiharitasi.collector.source.izsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.support.Fixtures;

class IzsuParserTest {

    private final IzsuParser parser = new IzsuParser();

    @Test
    void kayitliSayfa() {
        List<Outage> out = parser.parse(Fixtures.text("izsu/ariza-ve-bakim-bilgisi-sorgulama.html"));
        // Sayfada ayni satir mobil tabloda da var; tek kayit cikmali
        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.source()).isEqualTo("IZSU");
            assertThat(o.type()).isEqualTo(OutageType.WATER);
            assertThat(o.il()).isEqualTo("İZMİR");
            assertThat(o.ilce()).isEqualTo("BAYRAKLI");
            assertThat(o.mahalleler()).containsExactly("ALPASLAN", "BAYRAKLI", "ÇİÇEK", "FUAT EDİP BAKSI");
            assertThat(o.startsAt()).isEqualTo(Instant.parse("2026-09-10T19:00:00Z"));
            assertThat(o.endsAt()).isEqualTo(Instant.parse("2026-09-11T03:00:00Z"));
            assertThat(o.planned()).as("deplase calismasi planli").isTrue();
            assertThat(o.reason()).startsWith("Deplase çalışması - ");
        });
    }

    @Test
    void arizaSatiriPlanliDegil() {
        String html = """
                <table><thead><tr><th>İlçe</th><th>Mahalleler</th><th>İş Adı</th><th>Kesinti Başlangıç</th>
                <th>Kesinti Bitiş</th><th>Kısa Açıklama</th></tr></thead>
                <tbody><tr><td>KARŞIYAKA</td><td>Bostanlı Mah., Mavişehir</td><td>Ana Boru Arızası</td>
                <td>11.09.2026 - 10:00</td><td></td><td>Boru patladı</td></tr>
                <tr><td colspan="6">Kayıt yok</td></tr></tbody></table>""";
        assertThat(parser.parse(html)).singleElement().satisfies(o -> {
            assertThat(o.planned()).isFalse();
            assertThat(o.mahalleler()).containsExactly("BOSTANLI", "MAVİŞEHİR");
            assertThat(o.endsAt()).isNull();
        });
    }

    @Test
    void tabloYoksaHata() {
        assertThatThrownBy(() -> parser.parse("<html><body>Bakımdayız</body></html>"))
                .isInstanceOf(IllegalStateException.class);
    }
}
