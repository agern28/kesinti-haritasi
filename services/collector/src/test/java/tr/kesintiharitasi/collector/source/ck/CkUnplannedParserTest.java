package tr.kesintiharitasi.collector.source.ck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.source.ck.CkUnplannedParser.CkFault;
import tr.kesintiharitasi.collector.source.ck.CkUnplannedParser.TmLocation;
import tr.kesintiharitasi.collector.support.Fixtures;

class CkUnplannedParserTest {

    @Test
    void sadeceBildirimsizSatirlarKesintiNoyaGoreGruplanir() {
        List<CkFault> faults = CkUnplannedParser.faults(Fixtures.json("bedas/unplanned-retrieve-outages.json"));
        // 73 satirin 7'si "Bildirimsiz", hepsi tek ariza
        assertThat(faults).hasSize(1);
        CkFault f = faults.get(0);
        assertThat(f.outageNo()).isEqualTo("4851829");
        assertThat(f.transformers()).hasSize(7).contains("21528");
        assertThat(f.reportedAt()).isEqualTo(Instant.parse("2026-09-11T08:49:26Z"));
        assertThat(f.estimatedRepairAt()).isEqualTo(Instant.parse("2026-09-11T10:49:26Z"));
        assertThat(f.message()).startsWith("Şebeke arızası");
    }

    @Test
    void ayniTrafoTekrarEdiyorsaBirKezSayilir() {
        List<CkFault> faults = CkUnplannedParser.faults(Fixtures.json("aedas/unplanned-retrieve-outages.json"));
        assertThat(faults).hasSize(6);
        assertThat(faults).filteredOn(f -> f.outageNo().equals("2649714"))
                .singleElement().satisfies(f -> assertThat(f.transformers()).hasSize(36));
    }

    @Test
    void konumuBilinenTrafolarIlceIlceKayitOlur() {
        List<CkFault> faults = CkUnplannedParser.faults(Fixtures.json("bedas/unplanned-retrieve-outages.json"));
        List<Outage> out = CkUnplannedParser.toOutages(CkCompany.BEDAS, faults,
                Map.of("21528", new TmLocation("GAZİOSMANPAŞA", "BARBAROS HAYRETTİN PAŞA")));
        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.externalId()).isEqualTo("4851829/GAZİOSMANPAŞA");
            assertThat(o.il()).isEqualTo("İSTANBUL");
            assertThat(o.mahalleler()).containsExactly("BARBAROS HAYRETTİN PAŞA");
            assertThat(o.planned()).isFalse();
            assertThat(o.sourceUrl()).isEqualTo("https://kesinti.bedas.com.tr/");
        });
    }

    @Test
    void konumuOlmayanArizaAtlanir() {
        List<CkFault> faults = CkUnplannedParser.faults(Fixtures.json("bedas/unplanned-retrieve-outages.json"));
        assertThat(CkUnplannedParser.toOutages(CkCompany.BEDAS, faults, Map.of())).isEmpty();
    }

    @Test
    void aedasIlOnekiCozulur() {
        List<CkFault> faults = CkUnplannedParser.faults(Fixtures.json("aedas/unplanned-retrieve-outages.json"));
        Map<String, TmLocation> locations = Map.of(
                "637450", CkUnplannedParser.location(Fixtures.json("aedas/getlocation-637450.json")).orElseThrow(),
                "934321", CkUnplannedParser.location(Fixtures.json("aedas/getlocation-934321.json")).orElseThrow());
        List<Outage> out = CkUnplannedParser.toOutages(CkCompany.AEDAS, faults, locations);
        assertThat(out).hasSize(2);
        assertThat(out).anySatisfy(o -> {
            assertThat(o.externalId()).isEqualTo("2649779/MANAVGAT");
            assertThat(o.il()).isEqualTo("ANTALYA");
            assertThat(o.mahalleler()).containsExactly("KIZILOT");
        });
        assertThat(out).anySatisfy(o -> {
            assertThat(o.externalId()).isEqualTo("2649653/MERKEZ");
            assertThat(o.il()).isEqualTo("BURDUR");
            assertThat(o.ilce()).isEqualTo("MERKEZ");
            assertThat(o.mahalleler()).containsExactly("DÜĞER");
        });
    }

    @Test
    void getLocationCevabi() {
        assertThat(CkUnplannedParser.location(Fixtures.json("bedas/getlocation-28175.json")))
                .contains(new TmLocation("GAZİOSMANPAŞA", "BARBAROS HAYRETTİN PAŞA"));
        assertThat(CkUnplannedParser.location(Fixtures.JSON.readTree("{\"results\":[],\"status\":\"ZERO_RESULTS\"}")))
                .isEmpty();
    }
}
