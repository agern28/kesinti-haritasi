package tr.kesintiharitasi.collector.source.ck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.support.Fixtures;

class CkPlannedParserTest {

    private final CkPlannedParser parser = new CkPlannedParser();

    private List<Outage> parse(String company, CkCompany c) {
        return parser.parse(Fixtures.json(company + "/planned-getitemsdata.json"), c);
    }

    private static Outage byId(List<Outage> list, String externalId) {
        return list.stream().filter(o -> externalId.equals(o.externalId())).findFirst()
                .orElseThrow(() -> new AssertionError("yok: " + externalId));
    }

    @Test
    void bedasTumKayitlar() {
        List<Outage> out = parse("bedas", CkCompany.BEDAS);
        assertThat(out).hasSize(218);
        assertThat(out).allSatisfy(o -> {
            assertThat(o.source()).isEqualTo("BEDAS");
            assertThat(o.il()).isEqualTo("İSTANBUL");
            assertThat(o.type()).isEqualTo(OutageType.ELECTRICITY);
            assertThat(o.planned()).isTrue();
            assertThat(o.externalId()).isNotNull();
            assertThat(o.endsAt()).isAfter(o.startsAt());
            assertThat(o.sourceUrl()).isEqualTo(CkCompany.BEDAS.plannedPageUrl());
        });
    }

    @Test
    void bedasAyniIlceTekrarliysaTekKayit() {
        Outage o = byId(parse("bedas", CkCompany.BEDAS), "36880472");
        assertThat(o.ilce()).isEqualTo("ÇATALCA");
        assertThat(o.mahalleler()).containsExactly("KARACAKÖY MERKEZ");
        assertThat(o.startsAt()).isEqualTo(Instant.parse("2026-09-12T06:30:00Z"));
        assertThat(o.endsAt()).isEqualTo(Instant.parse("2026-09-12T14:30:00Z"));
        assertThat(o.lat()).isEqualTo(41.408683);
        assertThat(o.lon()).isEqualTo(28.38729);
    }

    @Test
    void bedasMesajindanMahalleler() {
        List<Outage> out = parse("bedas", CkCompany.BEDAS);
        assertThat(out).anySatisfy(o -> {
            assertThat(o.ilce()).isEqualTo("ESENLER");
            assertThat(o.mahalleler()).containsExactly("ORUÇREİS", "TURGUT REİS");
        });
        // "ESENLER ilce - mah  sk / HAVAALANI mah  sk / ORUÇREİS mah  sk": bos kayit atlanir
        assertThat(out).anySatisfy(o -> {
            assertThat(o.ilce()).isEqualTo("ESENLER");
            assertThat(o.mahalleler()).containsExactly("HAVAALANI", "ORUÇREİS");
        });
        assertThat(out).anySatisfy(o -> {
            assertThat(o.ilce()).isEqualTo("BAĞCILAR");
            assertThat(o.mahalleler()).containsExactly("100. YIL");
        });
        assertThat(out).allSatisfy(o -> assertThat(o.mahalleler()).noneMatch(m -> m.startsWith("MERKEZ-")));
    }

    @Test
    void aedasCokIlceliKayitIlceBasinaAyrilir() {
        List<Outage> out = parse("aedas", CkCompany.AEDAS);
        assertThat(out).hasSize(236);
        Outage aksu = byId(out, "10624791/AKSU");
        Outage muratpasa = byId(out, "10624791/MURATPAŞA");
        assertThat(aksu.il()).isEqualTo("ANTALYA");
        assertThat(aksu.mahalleler()).containsExactly("ALTINTAŞ");
        assertThat(muratpasa.mahalleler()).containsExactly("ERMENEK", "GÜZELOBA");
    }

    @Test
    void aedasMahIsaretliVeIsaretsizYerler() {
        Outage o = byId(parse("aedas", CkCompany.AEDAS), "10624928");
        assertThat(o.il()).isEqualTo("ANTALYA");
        assertThat(o.ilce()).isEqualTo("ELMALI");
        // "MERKEZ BAYRALAR" ile "MERKEZ BAYRALAR Mah." ayni yer; "MERKEZ KARAMIK" isaretsiz ama yeni yer
        assertThat(o.mahalleler()).containsExactly("BAYRALAR", "EYMİR", "KARAMIK", "YAKAÇİFTLİKKÖYÜ");
    }

    @Test
    void cedasSerbestMetinVeTrafoNotlari() {
        List<Outage> out = parse("cedas", CkCompany.CEDAS);
        assertThat(out).hasSize(102);

        Outage turhal = byId(out, "1365153");
        assertThat(turhal.il()).isEqualTo("TOKAT");
        assertThat(turhal.ilce()).isEqualTo("TURHAL");
        assertThat(turhal.mahalleler()).contains("BAHAR", "GÜNDOĞDU", "MEVLANA", "BOYACILAR");

        Outage zara = byId(out, "1363114/ZARA");
        assertThat(zara.il()).isEqualTo("SİVAS");
        assertThat(zara.mahalleler())
                .contains("ÖZKÖYLER GRUBU", "KAPLAN GRUBU", "ŞEREFİYE KÖYLER GRUBU", "DOĞANŞAR KÖYLERİ")
                .noneMatch(m -> m.contains("TRANSFORMATOR"));
        assertThat(byId(out, "1363114/HAFİK").ilce()).isEqualTo("HAFİK");

        Outage cekerek = byId(out, "1363925/ÇEKEREK");
        assertThat(cekerek.il()).isEqualTo("YOZGAT");
        assertThat(cekerek.mahalleler()).contains("ÖZÜKAVAK KASABASI", "KURTAĞILLI", "GÖNÜLYURDU KÖYLERİ");
    }

    @Test
    void ilceBasinaMahalleDagitimi() {
        List<CkCompany.Place> places = List.of(new CkCompany.Place("ANTALYA", "MURATPAŞA"),
                new CkCompany.Place("ANTALYA", "AKSU"));
        var map = CkPlannedParser.mahalleler(
                "ANTALYA,AKSU,MERKEZ ALTINTAŞ Mah. 31236 Sk.;ANTALYA,MURATPAŞA,MERKEZ ERMENEK Mah. 31242 Sk. bölgelerinde x",
                places);
        assertThat(map.get("AKSU")).containsExactly("ALTINTAŞ");
        assertThat(map.get("MURATPAŞA")).containsExactly("ERMENEK");
    }
}
