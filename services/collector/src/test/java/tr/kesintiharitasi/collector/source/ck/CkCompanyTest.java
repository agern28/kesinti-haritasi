package tr.kesintiharitasi.collector.source.ck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.source.ck.CkCompany.Place;

class CkCompanyTest {

    @Test
    void ilOnekliIlce() {
        assertThat(CkCompany.AEDAS.resolve("BURDUR_MERKEZ")).isEqualTo(new Place("BURDUR", "MERKEZ"));
        assertThat(CkCompany.AEDAS.resolve("ISPARTA_MERKEZ")).isEqualTo(new Place("ISPARTA", "MERKEZ"));
        assertThat(CkCompany.CEDAS.resolve("SIVAS_MERKEZ")).isEqualTo(new Place("SİVAS", "MERKEZ"));
    }

    @Test
    void ilceListesindenIl() {
        assertThat(CkCompany.AEDAS.resolve("MANAVGAT")).isEqualTo(new Place("ANTALYA", "MANAVGAT"));
        assertThat(CkCompany.AEDAS.resolve("EĞİRDİR")).isEqualTo(new Place("ISPARTA", "EĞİRDİR"));
        assertThat(CkCompany.AEDAS.resolve("YESILOVA")).isEqualTo(new Place("BURDUR", "YESILOVA"));
        assertThat(CkCompany.CEDAS.resolve("TURHAL")).isEqualTo(new Place("TOKAT", "TURHAL"));
        assertThat(CkCompany.BEDAS.resolve("ESENLER")).isEqualTo(new Place("İSTANBUL", "ESENLER"));
    }

    @Test
    void birdenFazlaIldeOlanAdAnaIleGider() {
        // AKSU hem Antalya hem Isparta ilcesi; onek yoksa sirketin ana ili
        assertThat(CkCompany.AEDAS.resolve("AKSU")).isEqualTo(new Place("ANTALYA", "AKSU"));
    }
}
