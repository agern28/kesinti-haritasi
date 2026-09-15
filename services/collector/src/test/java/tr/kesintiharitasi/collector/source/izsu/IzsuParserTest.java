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
    void kayitliSayfaBakimVeAriza() {
        List<Outage> out = parser.parse(Fixtures.text("izsu/ariza-ve-bakim-bilgisi-sorgulama.html"));
        // 1 planli bakim + 10 ariza. Ayni satirlar mobil tablolarda da var, onlar okunmuyor.
        assertThat(out).hasSize(11);
        assertThat(out).allSatisfy(o -> {
            assertThat(o.source()).isEqualTo("IZSU");
            assertThat(o.type()).isEqualTo(OutageType.WATER);
            assertThat(o.il()).isEqualTo("İZMİR");
        });
        assertThat(out).filteredOn(Outage::planned).singleElement().satisfies(o -> {
            assertThat(o.ilce()).isEqualTo("BAYRAKLI");
            assertThat(o.mahalleler()).containsExactly("ALPASLAN", "BAYRAKLI", "ÇİÇEK", "FUAT EDİP BAKSI");
            assertThat(o.startsAt()).isEqualTo(Instant.parse("2026-09-10T19:00:00Z"));
            assertThat(o.endsAt()).isEqualTo(Instant.parse("2026-09-11T03:00:00Z"));
            assertThat(o.reason()).startsWith("Deplase çalışması - ");
        });
        Outage fault = out.get(1);
        assertThat(fault.planned()).isFalse();
        assertThat(fault.ilce()).isEqualTo("BAYINDIR");
        assertThat(fault.mahalleler()).containsExactly("DEMİRCİLİK", "FATİH", "SADIKPAŞA");
        assertThat(fault.startsAt()).isEqualTo(Instant.parse("2026-09-11T08:20:00Z"));
        assertThat(fault.endsAt()).isEqualTo(Instant.parse("2026-09-11T11:20:00Z"));
        assertThat(fault.reason()).isEqualTo("Ana Boru Arızası - ANA BORU ARIZASI SEBEBİYLE SU KESİNTİSİ YAŞANMAKTADIR");
    }

    @Test
    void bakimYokArizaVar() {
        // 2026-09-15 sabahi canli sayfa: bakim bolumunde "Bakım bilgisi bulunmamaktadır.", 10 ariza
        List<Outage> out = parser.parse(Fixtures.text("izsu/ariza-var-bakim-yok-2026-09-15.html"));
        assertThat(out).hasSize(10).allSatisfy(o -> assertThat(o.planned()).isFalse());
        Outage first = out.get(0);
        assertThat(first.ilce()).isEqualTo("BEYDAĞ");
        assertThat(first.mahalleler()).containsExactly("ATATÜRK", "CUMHURİYET");
        assertThat(first.startsAt()).isEqualTo(Instant.parse("2026-09-15T07:49:00Z"));
        assertThat(first.endsAt()).isEqualTo(Instant.parse("2026-09-15T09:30:00Z"));
        assertThat(first.reason()).startsWith("Ana Boru Arızası - ");
    }

    @Test
    void ikiBolumDeBosHataDegil() {
        String html = """
                <html><body><div><p>Bakım bilgisi bulunmamaktadır.</p></div>
                <div><p>Arıza bilgisi bulunmamaktadır.</p></div></body></html>""";
        assertThat(parser.parse(html)).isEmpty();
    }

    @Test
    void arizaBolumuNeTabloNeMesajsaHata() {
        // Bakim bos, ariza bolumu taninmiyor: sayfa degismis olabilir, arizalari GONE yapmamak icin hata
        assertThatThrownBy(() -> parser.parse("<html><body><p>Bakım bilgisi bulunmamaktadır.</p></body></html>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ariza: yok");
    }

    @Test
    void bakimTablosundaArizaSatiriPlanliDegil() {
        String html = """
                <table><thead><tr><th>İlçe</th><th>Mahalleler</th><th>İş Adı</th><th>Kesinti Başlangıç</th>
                <th>Kesinti Bitiş</th><th>Kısa Açıklama</th></tr></thead>
                <tbody><tr><td>KARŞIYAKA</td><td>Bostanlı Mah., Mavişehir</td><td>Ana Boru Arızası</td>
                <td>11.09.2026 - 10:00</td><td></td><td>Boru patladı</td></tr>
                <tr><td colspan="6">Kayıt yok</td></tr></tbody></table>
                <p>Arıza bilgisi bulunmamaktadır.</p>""";
        assertThat(parser.parse(html)).singleElement().satisfies(o -> {
            assertThat(o.planned()).isFalse();
            assertThat(o.mahalleler()).containsExactly("BOSTANLI", "MAVİŞEHİR");
            assertThat(o.endsAt()).isNull();
        });
    }

    @Test
    void kesintiSuresi() {
        assertThat(IzsuParser.sure("15.09.2026 saat 10:49 ile 12:30 arasında"))
                .containsExactly(Instant.parse("2026-09-15T07:49:00Z"), Instant.parse("2026-09-15T09:30:00Z"));
        // bitis tarihsiz ve baslangictan once: gece yarisini geciyor
        assertThat(IzsuParser.sure("15.09.2026 saat 22:00 ile 02:00 arasında"))
                .containsExactly(Instant.parse("2026-09-15T19:00:00Z"), Instant.parse("2026-09-15T23:00:00Z"));
        assertThat(IzsuParser.sure("15.09.2026 saat 22:00 ile 16.09.2026 saat 3:15 arasında"))
                .containsExactly(Instant.parse("2026-09-15T19:00:00Z"), Instant.parse("2026-09-16T00:15:00Z"));
        assertThat(IzsuParser.sure("belirsiz")).isNull();
        assertThat(IzsuParser.sure(null)).isNull();
    }

    @Test
    void tabloYoksaHata() {
        assertThatThrownBy(() -> parser.parse("<html><body>Bakımdayız</body></html>"))
                .isInstanceOf(IllegalStateException.class);
    }
}
