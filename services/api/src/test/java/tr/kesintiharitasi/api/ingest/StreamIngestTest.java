package tr.kesintiharitasi.api.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import tr.kesintiharitasi.api.support.IntegrationTest;

/** Collector bicimindeki olaylar consumer group ile okunup veritabanina yaziliyor. */
class StreamIngestTest extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    private double count(String event, String result) {
        var c = registry.find("api.stream.events").tag("event", event).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private long pending() {
        return redis.opsForStream().pending("outage-events", "api").getTotalPendingMessages();
    }

    @Test
    void yeniTekrarVeGone() {
        String key = unique("TEST:");
        String ilce = unique("FATIH");
        Instant now = Instant.now();
        var p = payload("İSTANBUL", ilce, List.of("BALAT"), "WATER", false, now.minusSeconds(60), now.plusSeconds(600));
        double noopBefore = count("NEW", "noop");

        publish("NEW", key, "h1", p, now);
        await().atMost(Duration.ofSeconds(10)).until(() -> rowCount(key) == 1);

        // Collector cokup ayni olayi tekrar yazarsa (at-least-once): satir sayisi degismez
        publish("NEW", key, "h1", p, now.plusSeconds(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> count("NEW", "noop") > noopBefore);
        assertThat(rowCount(key)).isEqualTo(1);

        publish("GONE", key, "h1", null, now.plusSeconds(300));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("select gone_at is not null from outage where dedup_key = :k")
                .param("k", key).query(Boolean.class).single());

        await().atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);
    }

    @Test
    void bozukOlayOnaylanipAtlanir() {
        double before = count("NEW", "bad");
        redis.opsForStream().add(StreamRecords.string(java.util.Map.of("event", "NEW", "dedupKey", unique("TEST:"),
                "contentHash", "x", "scannedAt", Instant.now().toString(), "payload", "{bozuk json"))
                .withStreamKey("outage-events"));
        await().atMost(Duration.ofSeconds(10)).until(() -> count("NEW", "bad") > before);
        await().atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);
    }
}
