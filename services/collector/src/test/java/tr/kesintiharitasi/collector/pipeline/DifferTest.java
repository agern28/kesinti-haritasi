package tr.kesintiharitasi.collector.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static tr.kesintiharitasi.collector.support.TestOutages.outage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;

class DifferTest {

    private static final FeedId FEED = new FeedId("TEST", "planned");
    private final Differ differ = new Differ();

    private Differ.DiffResult diff(Map<String, String> previous, Outage... current) {
        return differ.diff(FEED, previous, List.of(current), Instant.parse("2026-09-13T10:00:00Z"));
    }

    @Test
    void ilkTaramadaHepsiYeni() {
        Differ.DiffResult d = diff(Map.of(), outage("1", "ESENLER", "a"), outage("2", "FATİH", "b"));
        assertThat(d.events()).extracting(OutageEvent::type).containsExactly(EventType.NEW, EventType.NEW);
        assertThat(d.snapshot()).containsOnlyKeys("TEST:1", "TEST:2");
    }

    @Test
    void degisiklikYoksaOlayYok() {
        Map<String, String> prev = diff(Map.of(), outage("1", "ESENLER", "a")).snapshot();
        Differ.DiffResult d = diff(prev, outage("1", "ESENLER", "a"));
        assertThat(d.events()).isEmpty();
        assertThat(d.snapshot()).isEqualTo(prev);
    }

    @Test
    void degisenUpdatedKaybolanGone() {
        Map<String, String> prev = diff(Map.of(), outage("1", "ESENLER", "a"), outage("2", "FATİH", "b")).snapshot();
        Differ.DiffResult d = diff(prev, outage("1", "ESENLER", "sebep degisti"), outage("3", "ŞİŞLİ", "c"));
        assertThat(d.events()).extracting(OutageEvent::type, OutageEvent::dedupKey).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(EventType.UPDATED, "TEST:1"),
                org.assertj.core.groups.Tuple.tuple(EventType.NEW, "TEST:3"),
                org.assertj.core.groups.Tuple.tuple(EventType.GONE, "TEST:2"));
        OutageEvent gone = d.events().stream().filter(e -> e.type() == EventType.GONE).findFirst().orElseThrow();
        assertThat(gone.outage()).isNull();
        assertThat(d.counts()).containsEntry(EventType.NEW, 1).containsEntry(EventType.UPDATED, 1)
                .containsEntry(EventType.GONE, 1);
    }

    @Test
    void tekrarEdenKayitTekSayilir() {
        Differ.DiffResult d = diff(Map.of(), outage("1", "ESENLER", "a"), outage("1", "ESENLER", "a"));
        assertThat(d.events()).hasSize(1);
        assertThat(d.duplicates()).isEqualTo(1);
    }
}
