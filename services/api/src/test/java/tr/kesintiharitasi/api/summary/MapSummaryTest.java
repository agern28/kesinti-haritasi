package tr.kesintiharitasi.api.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.api.support.IntegrationTest;

/** Harita ozeti Redis'te; degisen ilce cache yeniden kurulmadan guncelleniyor. */
class MapSummaryTest extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    private double cache(String result) {
        return registry.get("api.summary.cache").tag("result", result).counter().count();
    }

    private JsonNode district(String il, String ilce) throws Exception {
        for (JsonNode d : getJson("/api/map/summary").path("districts")) {
            if (d.path("il").asString().equals(il) && d.path("ilce").asString().equals(ilce)) {
                return d;
            }
        }
        return null;
    }

    @Test
    void ozetAktifKesintileriSayarVeCanliGuncellenir() throws Exception {
        String il = unique("IL");
        String a = unique("A");
        String b = unique("B");
        Instant now = Instant.now();
        List<String> keys = List.of(unique("TEST:"), unique("TEST:"), unique("TEST:"), unique("TEST:"));
        publish("NEW", keys.get(0), "h", payload(il, a, List.of("X"), "ELECTRICITY", true, now.minusSeconds(60), now.plusSeconds(600)), now);
        publish("NEW", keys.get(1), "h", payload(il, a, List.of("Y"), "ELECTRICITY", false, now.minusSeconds(60), null), now);
        publish("NEW", keys.get(2), "h", payload(il, b, List.of("Z"), "WATER", true, now.minusSeconds(60), now.plusSeconds(600)), now);
        publish("NEW", keys.get(3), "h", payload(il, a, List.of("W"), "ELECTRICITY", true, now.plusSeconds(3600), now.plusSeconds(7200)), now);
        await().atMost(Duration.ofSeconds(10)).until(() -> keys.stream().allMatch(k -> rowCount(k) == 1));

        redis.delete(SummaryCache.READY);
        double missBefore = cache("miss");
        JsonNode da = district(il, a);
        assertThat(cache("miss")).isEqualTo(missBefore + 1);
        assertThat(da.path("total").asInt()).as("gelecekteki kesinti sayilmaz").isEqualTo(2);
        assertThat(da.path("planned").asInt()).isEqualTo(1);
        assertThat(da.path("unplanned").asInt()).isEqualTo(1);
        assertThat(da.path("byType").path("ELECTRICITY").asInt()).isEqualTo(2);
        assertThat(district(il, b).path("byType").path("WATER").asInt()).isEqualTo(1);

        double hitBefore = cache("hit");
        String extra = unique("TEST:");
        publish("NEW", extra, "h", payload(il, a, List.of("V"), "GAS", false, now.minusSeconds(10), null), now);
        await().atMost(Duration.ofSeconds(10)).until(() -> district(il, a).path("total").asInt() == 3);
        assertThat(cache("hit")).isGreaterThan(hitBefore);
        assertThat(cache("miss")).as("cache yeniden kurulmadi").isEqualTo(missBefore + 1);

        publish("GONE", extra, "h", null, Instant.now().plusSeconds(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> district(il, a).path("total").asInt() == 2);

        publish("GONE", keys.get(2), "h", null, Instant.now().plusSeconds(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> district(il, b) == null);
    }
}
