package tr.kesintiharitasi.collector.source.ck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.http.HttpResult;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.support.Fixtures;

/**
 * ÇEDAŞ'in yuk dengeleyicisinin arkasindaki eski sunucu. Iki kayitli cevap:
 * guncel sunucu (2026-09-10..14 kesintileri) ve eski sunucu (2024-06-12..14, 2026-09-15'te alindi).
 */
class CkPlannedCollectorTest {

    private static final String CURRENT = "cedas/planned-getitemsdata.json";
    private static final String STALE = "cedas/planned-eski-sunucu-2026-09-15.json";

    private final PoliteHttpClient http = mock(PoliteHttpClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC);
    private final CkPlannedCollector collector = new CkPlannedCollector(CkCompany.CEDAS, http, Fixtures.JSON, clock);

    private static HttpResult ok(byte[] body) {
        return new HttpResult(200, URI.create("https://www.cedas.com.tr/GetItemsData"), "application/json", body);
    }

    @Test
    void eskiSunucuCevabindaBirKezDahaIsteniyor() throws Exception {
        when(http.get(CkCompany.CEDAS.plannedUrl())).thenReturn(ok(Fixtures.bytes(STALE)), ok(Fixtures.bytes(CURRENT)));
        CollectResult result = collector.collect();
        assertThat(result.outages()).isNotEmpty()
                .allSatisfy(o -> assertThat(o.startsAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z")));
        verify(http, times(2)).get(CkCompany.CEDAS.plannedUrl());
    }

    @Test
    void ikiDenemedeDeEskiyseTaramaBasarisiz() throws Exception {
        when(http.get(CkCompany.CEDAS.plannedUrl())).thenReturn(ok(Fixtures.bytes(STALE)));
        assertThatThrownBy(collector::collect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eski veri")
                .hasMessageContaining("2024-06-14");
        verify(http, times(2)).get(CkCompany.CEDAS.plannedUrl());
    }

    @Test
    void guncelCevaptaTekIstek() throws Exception {
        when(http.get(CkCompany.CEDAS.plannedUrl())).thenReturn(ok(Fixtures.bytes(CURRENT)));
        assertThat(collector.collect().outages()).isNotEmpty();
        verify(http, times(1)).get(CkCompany.CEDAS.plannedUrl());
    }

    @Test
    void bosListeEskiSayilmaz() throws Exception {
        when(http.get(CkCompany.CEDAS.plannedUrl())).thenReturn(ok("[]".getBytes(StandardCharsets.UTF_8)));
        assertThat(collector.collect().outages()).isEmpty();
        verify(http, times(1)).get(CkCompany.CEDAS.plannedUrl());
    }
}
