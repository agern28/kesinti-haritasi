package tr.kesintiharitasi.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tr.kesintiharitasi.api.outage.IncomingOutage;
import tr.kesintiharitasi.api.outage.OutageRepository;
import tr.kesintiharitasi.api.support.IntegrationTest;
import tr.kesintiharitasi.api.support.SseTestClient;

/** Kaynaktan olay gelmeden, saati gelen planli kesinti baslar ve biter; tarayiciya yine olay gider. */
class TransitionSweeperTest extends IntegrationTest {

    @Autowired
    TransitionSweeper sweeper;

    @Autowired
    OutageRepository repository;

    private IncomingOutage outage(String ilce, Instant start, Instant end) {
        return new IncomingOutage("TEST", null, "ELECTRICITY", true, "KAYSERİ", ilce, List.of("M"), start, end,
                "bakim", "https://example.org", null, null);
    }

    @Test
    void saatiGelenKesintiBaslarVeBiter() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String started = unique("BASLAYAN");
        String finished = unique("BITEN");
        String steady = unique("SUREN");
        repository.upsert(unique("TEST:"), "h", outage(started, now.minusSeconds(20), now.plusSeconds(3600)), now.minusSeconds(120));
        repository.upsert(unique("TEST:"), "h", outage(finished, now.minusSeconds(7200), now.minusSeconds(10)), now.minusSeconds(120));
        repository.upsert(unique("TEST:"), "h", outage(steady, now.minusSeconds(7200), now.plusSeconds(7200)), now.minusSeconds(120));

        redis.delete(TransitionSweeper.LOCK);
        redis.opsForValue().set(TransitionSweeper.LAST, now.minusSeconds(60).toString());
        try (SseTestClient client = SseTestClient.connect(port, null).awaitConnected()) {
            assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(2);
            assertThat(client.next(e -> e.data().contains(started), Duration.ofSeconds(10)).name())
                    .isEqualTo(LiveEvents.UPDATED);
            assertThat(client.next(e -> e.data().contains(finished), Duration.ofSeconds(10)).name())
                    .isEqualTo(LiveEvents.ENDED);
        }
        assertThat(sweeper.sweep()).as("kilit baska turdayken ikinci calisma yok").isEqualTo(-1);
    }
}
