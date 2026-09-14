package tr.kesintiharitasi.collector.source.kcetas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.pipeline.Differ;
import tr.kesintiharitasi.collector.support.Fixtures;

class KcetasParserTest {

    private final KcetasParser parser = new KcetasParser();

    @Test
    void ozelliklerKesintiyeDonusur() {
        List<Outage> out = parser.parse(Fixtures.json("kcetas/kesinti-sorgu-2026-09-11.json"));
        assertThat(out).hasSize(26);
        assertThat(out).allSatisfy(o -> {
            assertThat(o.source()).isEqualTo("KCETAS");
            assertThat(o.il()).isEqualTo("KAYSERİ");
            assertThat(o.planned()).isTrue();
            assertThat(o.externalId()).isNull();
            assertThat(o.lat()).isBetween(37.5, 39.8);
            assertThat(o.lon()).isBetween(34.5, 37.5);
        });
        Outage first = out.get(0);
        assertThat(first.ilce()).isEqualTo("PINARBAŞI");
        assertThat(first.mahalleler()).containsExactly("SOLAKLAR");
        assertThat(first.startsAt()).isEqualTo(Instant.parse("2026-09-11T06:00:00Z"));
        assertThat(first.endsAt()).isEqualTo(Instant.parse("2026-09-11T14:00:00Z"));
    }

    @Test
    void ayniMahalleVeSaatTekKayit() {
        List<Outage> out = parser.parse(Fixtures.json("kcetas/kesinti-sorgu-2026-09-11.json"));
        Differ.DiffResult diff = new Differ().diff(new FeedId("KCETAS", "planned"), Map.of(), out, Instant.now());
        assertThat(diff.snapshot()).hasSize(24);
        assertThat(diff.duplicates()).isEqualTo(2);
    }

    @Test
    void adrestenMahalle() {
        assertThat(KcetasParser.mahalle(" YAKUTİYE MAH. BÜNYAN KAYSERİ", "BÜNYAN", "KAYSERİ")).isEqualTo("YAKUTİYE");
        assertThat(KcetasParser.mahalle(" KÖPRÜBAŞI MAH. GEMEREK SİVAS", "GEMEREK", "SİVAS")).isEqualTo("KÖPRÜBAŞI");
        assertThat(KcetasParser.mahalle("", "BÜNYAN", "KAYSERİ")).isNull();
    }

    @Test
    void adrestenIl() {
        assertThat(KcetasParser.il(" KÖPRÜBAŞI MAH. GEMEREK SİVAS", "GEMEREK")).isEqualTo("SİVAS");
        assertThat(KcetasParser.il(" SOLAKLAR MAH. PINARBAŞI KAYSERİ", "PINARBAŞI")).isEqualTo("KAYSERİ");
        // il yazilmamissa ya da ilceden sonra birden fazla kelime varsa Kayseri
        assertThat(KcetasParser.il(" YAKUTİYE MAH. BÜNYAN", "BÜNYAN")).isEqualTo("KAYSERİ");
        assertThat(KcetasParser.il(" A MAH. BÜNYAN B C", "BÜNYAN")).isEqualTo("KAYSERİ");
        assertThat(KcetasParser.il("", "BÜNYAN")).isEqualTo("KAYSERİ");
    }

    @Test
    void kayseriDisindakiIlce() {
        // 2026-09-14 canli cevabindan: KCETAŞ Sivas'in Gemerek ilcesine de hizmet veriyor
        String json = """
                {"success":true,"features":[{"type":"Feature","properties":{"ilce":"GEMEREK",
                "adres":" KÖPRÜBAŞI MAH. GEMEREK SİVAS","tur":"Bildirimli",
                "baslangic":"2026-09-14T09:00:00","bitis":"2026-09-14T17:00:00"},"geometry":null}]}""";
        Outage o = parser.parse(Fixtures.JSON.readTree(json)).get(0);
        assertThat(o.il()).isEqualTo("SİVAS");
        assertThat(o.ilce()).isEqualTo("GEMEREK");
        assertThat(o.mahalleler()).containsExactly("KÖPRÜBAŞI");
    }

    @Test
    void basarisizCevapHataOlur() {
        assertThatThrownBy(() -> parser.parse(Fixtures.JSON.readTree("{\"success\":false}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.parse(Fixtures.JSON.readTree("{\"success\":true,\"sistem_bakimda\":true}")))
                .isInstanceOf(IllegalStateException.class);
    }
}
