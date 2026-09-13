package tr.kesintiharitasi.collector.source.iski;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.http.HttpResult;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.support.Fixtures;
import tr.kesintiharitasi.collector.support.InMemoryStores;

class IbbIskiTest {

    @Test
    void xlsxIlkSayfaOkunur() throws Exception {
        List<List<String>> rows = XlsxReader.readFirstSheet(Fixtures.bytes("iski/ibb-su-kesintileri-2023-2024.xlsx"));
        assertThat(rows).hasSize(6411);
        assertThat(rows.get(0)).containsExactly("ILCE", "KESİNTİ SEBEP", "ARIZA KESİNTİ TARİHİ", "ARIZA BİTİS TARİHİ",
                "CALISMA YERİ", "MAHALLE");
    }

    @Test
    void satirlarKesintiyeDonusur() throws Exception {
        List<List<String>> rows = XlsxReader.readFirstSheet(Fixtures.bytes("iski/ibb-su-kesintileri-2023-2024.xlsx"));
        IbbWaterOutageParser.Result r = new IbbWaterOutageParser().parse(rows, IbbIskiCollector.DATASET_URL);
        assertThat(r.skippedRows()).isZero();
        assertThat(r.outages()).hasSize(6410);
        assertThat(r.outages()).allSatisfy(o -> {
            assertThat(o.source()).isEqualTo("ISKI");
            assertThat(o.type()).isEqualTo(OutageType.WATER);
            assertThat(o.il()).isEqualTo("İSTANBUL");
            assertThat(o.planned()).isFalse();
        });
        Outage first = r.outages().get(0);
        assertThat(first.ilce()).isEqualTo("ADALAR");
        assertThat(first.mahalleler()).containsExactly("BURGAZADA");
        assertThat(first.startsAt()).isEqualTo(Instant.parse("2024-02-12T10:30:10Z"));
        assertThat(first.endsAt()).isEqualTo(Instant.parse("2024-02-12T17:30:00Z"));
        assertThat(first.reason()).isEqualTo("100 MM ÇAPLI ŞEBEKE HATTI ARIZASI - BURGAZADA GÖNÜLLÜ CAD.ÜZERINDE");
        assertThat(r.outages().get(3).mahalleler()).containsExactly("MADEN", "NİZAM");
    }

    @Test
    void veriSetiSayfasindakiDosyaLinkleri() {
        List<String> links = IbbIskiCollector.xlsxLinks(Fixtures.text("iski/ibb-dataset-page.html"));
        assertThat(links).hasSize(2).allSatisfy(l -> assertThat(l)
                .startsWith("https://data.ibb.gov.tr/dataset/9bc78003-8dc8-4772-8f3f-a5ad7b8c8120/resource/")
                .endsWith(".xlsx"));
    }

    @Test
    void dosyalarDegismediyseIndirilmez() throws Exception {
        PoliteHttpClient http = mock(PoliteHttpClient.class);
        byte[] xlsx = Fixtures.bytes("iski/ibb-su-kesintileri-2023-2024.xlsx");
        when(http.get(anyString())).thenReturn(result(xlsx));
        when(http.get(IbbIskiCollector.DATASET_URL))
                .thenReturn(result(Fixtures.text("iski/ibb-dataset-page.html").getBytes(StandardCharsets.UTF_8)));
        InMemoryStores.Snapshots snapshots = new InMemoryStores.Snapshots();
        IbbIskiCollector c = new IbbIskiCollector(http, snapshots, new InMemoryStores.State());

        CollectResult first = c.collect();
        assertThat(first.unchanged()).isFalse();
        assertThat(first.outages()).hasSize(2 * 6410);
        first.afterCommit().run();
        snapshots.save(c.id(), Map.of("ISKI:h:x", "y"));

        assertThat(c.collect().unchanged()).isTrue();
        // sayfa iki kez, her dosya bir kez
        verify(http, times(2)).get(IbbIskiCollector.DATASET_URL);
        verify(http, times(4)).get(anyString());
    }

    private static HttpResult result(byte[] body) {
        return new HttpResult(200, URI.create(IbbIskiCollector.DATASET_URL), "text/html; charset=utf-8", body);
    }
}
