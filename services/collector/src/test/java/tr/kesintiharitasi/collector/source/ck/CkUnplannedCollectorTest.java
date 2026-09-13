package tr.kesintiharitasi.collector.source.ck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.http.HttpResult;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.support.Fixtures;
import tr.kesintiharitasi.collector.support.InMemoryStores;

/** Trafo konum cache'i ve tarama basina istek siniri. HTTP sahte, fixture'dan. */
class CkUnplannedCollectorTest {

    private final PoliteHttpClient http = mock(PoliteHttpClient.class);
    private final InMemoryStores.State state = new InMemoryStores.State();

    private static HttpResult ok(byte[] body) {
        return new HttpResult(200, URI.create("https://kesintiapi.ckenerji.com.tr/"), "application/json", body);
    }

    @BeforeEach
    void stubs() throws Exception {
        when(http.get(argThat(u -> u != null && u.contains("GetLocation"))))
                .thenReturn(ok("{\"results\":[],\"status\":\"ZERO_RESULTS\"}".getBytes(StandardCharsets.UTF_8)));
        when(http.get(CkCompany.BEDAS.unplannedUrl()))
                .thenReturn(ok(Fixtures.bytes("bedas/unplanned-retrieve-outages.json")));
        when(http.get(CkCompany.BEDAS.locationUrl("21528")))
                .thenReturn(ok(Fixtures.bytes("bedas/getlocation-28175.json")));
    }

    @Test
    void konumlarCacheleniyorVeTekrarSorulmuyor() throws Exception {
        CkUnplannedCollector c = new CkUnplannedCollector(CkCompany.BEDAS, http, Fixtures.JSON, state, 100);

        CollectResult first = c.collect();
        assertThat(first.outages()).singleElement()
                .satisfies(o -> assertThat(o.externalId()).isEqualTo("4851829/GAZİOSMANPAŞA"));

        CollectResult second = c.collect();
        assertThat(second.outages()).isEqualTo(first.outages());
        // 7 trafo, her biri bir kez (bulunamayanlar da "-" olarak cache'de)
        verify(http, times(7)).get(argThat(u -> u != null && u.contains("GetLocation")));
        verify(http, times(1)).get(CkCompany.BEDAS.locationUrl("21528"));
    }

    @Test
    void taramaBasinaIstekSiniri() throws Exception {
        CkUnplannedCollector c = new CkUnplannedCollector(CkCompany.BEDAS, http, Fixtures.JSON, state, 0);
        assertThat(c.collect().outages()).isEmpty();
        verify(http, times(0)).get(argThat(u -> u != null && u.contains("GetLocation")));
    }
}
