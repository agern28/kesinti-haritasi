package tr.kesintiharitasi.api.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.api.support.IntegrationTest;

/** Collector'in collector:status hash'ine yazdiklarindan kaynak bazinda son tarama ve gecikme. */
class SourcesTest extends IntegrationTest {

    private void status(String field, long intervalSeconds, Instant lastSuccess) {
        String value = "{\"source\":\"" + field.split("/")[0] + "\",\"feed\":\"" + field.split("/")[1]
                + "\",\"intervalSeconds\":" + intervalSeconds
                + (lastSuccess == null ? "" : ",\"lastSuccessAt\":\"" + lastSuccess + "\",\"lastItems\":12") + "}";
        redis.opsForHash().put("collector:status", field, value);
    }

    @Test
    void sonTaramaVeGecikme() throws Exception {
        redis.delete("collector:status");
        Instant now = Instant.now();
        status("BEDAS/planned", 900, now.minusSeconds(120));
        status("BEDAS/unplanned", 300, now.minusSeconds(60));
        status("IZSU/all", 300, now.minusSeconds(3600));
        status("YENI/planned", 900, null);

        JsonNode list = getJson("/api/sources");
        List<String> order = new ArrayList<>();
        list.forEach(s -> order.add(s.path("source").asString()));
        assertThat(order).startsWith("BEDAS", "AEDAS", "CEDAS", "KCETAS", "IZSU", "ISKI").endsWith("YENI");

        JsonNode bedas = list.get(0);
        assertThat(bedas.path("name").asString()).isEqualTo("BEDAŞ");
        assertThat(bedas.path("stale").asBoolean()).isFalse();
        assertThat(Instant.parse(bedas.path("lastSuccessAt").asString())).isEqualTo(now.minusSeconds(60));
        assertThat(bedas.path("feeds")).hasSize(2);

        assertThat(list.get(4).path("stale").asBoolean()).as("IZSU 1 saattir taranmadi").isTrue();
        JsonNode kcetas = list.get(3);
        assertThat(kcetas.path("stale").asBoolean()).as("hic durum yok").isTrue();
        assertThat(kcetas.path("feeds")).isEmpty();
    }
}
