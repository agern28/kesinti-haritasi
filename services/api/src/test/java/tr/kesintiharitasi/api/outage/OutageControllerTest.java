package tr.kesintiharitasi.api.outage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.api.support.IntegrationTest;

class OutageControllerTest extends IntegrationTest {

    @Test
    void streamdenGelenKesintiListedeVeTekKayitOlarak() throws Exception {
        String ilce = unique("ESENLER");
        String key = unique("TEST:");
        Instant now = Instant.now();
        publish("NEW", key, "h1", payload("İSTANBUL", ilce, List.of("ORUÇREİS"), "ELECTRICITY", true,
                now.minusSeconds(600), now.plusSeconds(3600)), now);

        await().atMost(Duration.ofSeconds(10)).until(() -> rowCount(key) == 1);

        JsonNode page = getJson("/api/outages?ilce=" + ilce.toLowerCase() + "&active=true");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("ilce").asString()).isEqualTo(ilce);
        assertThat(item.path("active").asBoolean()).isTrue();
        assertThat(item.path("mahalleler").get(0).asString()).isEqualTo("ORUÇREİS");

        JsonNode one = getJson("/api/outages/" + item.path("id").asString());
        assertThat(one.path("id").asString()).isEqualTo(item.path("id").asString());
    }

    @Test
    void hataliIstekler() throws Exception {
        assertThat(get("/api/outages?type=NUKLEER").statusCode()).isEqualTo(400);
        assertThat(get("/api/outages?size=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/outages/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/api/outages/abc").statusCode()).isEqualTo(400);
    }

    @Test
    void sayfaBoyutuSinirli() throws Exception {
        assertThat(getJson("/api/outages?size=10000").path("size").asInt()).isEqualTo(OutageController.MAX_SIZE);
    }
}
