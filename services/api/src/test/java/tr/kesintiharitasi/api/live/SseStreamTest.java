package tr.kesintiharitasi.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.api.support.IntegrationTest;
import tr.kesintiharitasi.api.support.SseTestClient;
import tr.kesintiharitasi.api.support.SseTestClient.Event;

/** Veritabanina dusen degisiklik aninda SSE istemcisine gidiyor; kopan istemci kacirdiklarini aliyor. */
class SseStreamTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private void newOutage(String key, String ilce) {
        Instant now = Instant.now();
        publish("NEW", key, "h1", payload("İZMİR", ilce, List.of("ALSANCAK"), "WATER", true,
                now.minusSeconds(60), now.plusSeconds(3600)), now);
    }

    @Test
    void olusturmaVeBitisOlaylari() throws Exception {
        String ilce = unique("KONAK");
        String key = unique("TEST:");
        try (SseTestClient client = SseTestClient.connect(port, null).awaitConnected()) {
            newOutage(key, ilce);
            Event created = client.next(e -> e.data().contains(ilce), WAIT);
            assertThat(created.name()).isEqualTo(LiveEvents.CREATED);
            assertThat(StreamIds.valid(created.id())).isTrue();
            assertThat(json.readTree(created.data()).path("mahalleler").get(0).asString()).isEqualTo("ALSANCAK");

            publish("GONE", key, "h1", null, Instant.now().plusSeconds(1));
            Event ended = client.next(e -> e.data().contains(ilce), WAIT);
            assertThat(ended.name()).isEqualTo(LiveEvents.ENDED);
            assertThat(StreamIds.compare(ended.id(), created.id())).isPositive();
        }
    }

    @Test
    void zatenBitmisKesintiYazilirAmaYayinlanmaz() throws Exception {
        String old = unique("ESKI");
        String fresh = unique("YENI");
        String oldKey = unique("TEST:");
        try (SseTestClient client = SseTestClient.connect(port, null).awaitConnected()) {
            Instant now = Instant.now();
            // İBB'nin gecmis verisi gibi: 2024'te baslamis ve bitmis
            publish("NEW", oldKey, "h1", payload("İSTANBUL", old, List.of("MADEN"), "WATER", false,
                    Instant.parse("2024-02-12T10:30:00Z"), Instant.parse("2024-02-12T17:30:00Z")), now);
            newOutage(unique("TEST:"), fresh);

            Event e = client.next(x -> x.data().contains(fresh) || x.data().contains(old), WAIT);
            assertThat(e.data()).contains(fresh);
            await().atMost(WAIT).until(() -> rowCount(oldKey) == 1);
        }
    }

    @Test
    void periyodikHeartbeat() throws Exception {
        try (SseTestClient client = SseTestClient.connect(port, null).awaitConnected()) {
            assertThat(client.sawComment("hb", Duration.ofSeconds(3))).isTrue();
        }
    }

    @Test
    void kopanIstemciLastEventIdIleKacirdiklariniAlir() throws Exception {
        String ilce = unique("BUCA");
        String first = unique("TEST:");
        String second = unique("TEST:");
        String third = unique("TEST:");
        String lastSeen;
        try (SseTestClient client = SseTestClient.connect(port, null).awaitConnected()) {
            newOutage(first, ilce);
            lastSeen = client.next(e -> e.data().contains(ilce), WAIT).id();
        }
        // Istemci yokken iki kesinti daha geliyor
        newOutage(second, ilce);
        newOutage(third, ilce);
        await().atMost(WAIT).until(() -> rowCount(second) == 1 && rowCount(third) == 1);

        try (SseTestClient client = SseTestClient.connect(port, lastSeen).awaitConnected()) {
            Event a = client.next(e -> e.data().contains(ilce), WAIT);
            Event b = client.next(e -> e.data().contains(ilce), WAIT);
            assertThat(a.data()).doesNotContain(first).as("zaten gorulen olay tekrar gelmez");
            assertThat(List.of(a.name(), b.name())).containsOnly(LiveEvents.CREATED);
            assertThat(StreamIds.compare(a.id(), lastSeen)).isPositive();
            assertThat(StreamIds.compare(b.id(), a.id())).isPositive();
        }
    }

    @Test
    void cokEskiLastEventIdResyncIster() throws Exception {
        newOutage(unique("TEST:"), unique("BORNOVA"));
        await().atMost(WAIT).until(() -> redis.opsForStream().size("sse-events") > 0);
        try (SseTestClient client = SseTestClient.connect(port, "1-0").awaitConnected()) {
            assertThat(client.next(WAIT).name()).isEqualTo(LiveEvents.RESYNC);
        }
    }

    @Test
    void streamIdKarsilastirma() {
        assertThat(StreamIds.compare("1789285448129-1", "1789285448129-0")).isPositive();
        assertThat(StreamIds.compare("1789285448128-9", "1789285448129-0")).isNegative();
        assertThat(StreamIds.compare("5-0", null)).isPositive();
        assertThat(StreamIds.valid("abc")).isFalse();
    }
}
