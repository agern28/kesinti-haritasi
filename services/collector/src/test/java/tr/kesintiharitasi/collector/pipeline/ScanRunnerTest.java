package tr.kesintiharitasi.collector.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static tr.kesintiharitasi.collector.support.TestOutages.outage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;
import tr.kesintiharitasi.collector.support.InMemoryStores;

class ScanRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final InMemoryStores.Snapshots snapshots = new InMemoryStores.Snapshots();
    private final InMemoryStores.Publisher publisher = new InMemoryStores.Publisher();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CollectorMetrics metrics = new CollectorMetrics(registry);
    private final ScanRunner runner = new ScanRunner(snapshots, publisher, metrics,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, d -> { });

    /** Her collect() cagrisinda listedeki bir sonraki sonucu doner. */
    private static final class ScriptedCollector implements SourceCollector {
        private final FeedId id;
        private final List<Object> script = new ArrayList<>();
        private int calls;

        ScriptedCollector(String source) {
            this.id = new FeedId(source, "planned");
        }

        ScriptedCollector then(Object resultOrException) {
            script.add(resultOrException);
            return this;
        }

        @Override
        public FeedId id() {
            return id;
        }

        @Override
        public Duration defaultInterval() {
            return Duration.ofMinutes(15);
        }

        @Override
        public CollectResult collect() throws Exception {
            Object next = script.get(Math.min(calls++, script.size() - 1));
            if (next instanceof Exception e) {
                throw e;
            }
            return (CollectResult) next;
        }
    }

    private static CollectResult of(Outage... o) {
        return CollectResult.of(List.of(o));
    }

    @Test
    void birKaynakHataVerinceDigeriEtkilenmez() {
        ScriptedCollector broken = new ScriptedCollector("BOZUK").then(new IOException("HTTP 503"));
        ScriptedCollector healthy = new ScriptedCollector("SAGLAM").then(of(outage("1", "ESENLER", "a")));

        assertThat(runner.run(broken)).isFalse();
        assertThat(runner.run(healthy)).isTrue();

        assertThat(metrics.errorCount(broken.id())).isEqualTo(1);
        assertThat(metrics.lastSuccessEpochSeconds(broken.id())).isZero();
        assertThat(metrics.lastSuccessEpochSeconds(healthy.id())).isEqualTo(NOW.getEpochSecond());
        assertThat(publisher.all()).hasSize(1);
    }

    @Test
    void ikinciTaramadaDegisiklikYoksaStreameBirSeyGitmez() {
        ScriptedCollector c = new ScriptedCollector("X").then(of(outage("1", "ESENLER", "a"), outage("2", "FATİH", "b")));
        runner.run(c);
        runner.run(c);
        assertThat(publisher.batches).hasSize(1);
        assertThat(publisher.all()).extracting(OutageEvent::type).containsOnly(EventType.NEW);
        assertThat(registry.get("collector.items").tag("source", "X").counter().count()).isEqualTo(4);
    }

    @Test
    void hataSnapshotiBozmazGoneUretmez() {
        AtomicInteger commits = new AtomicInteger();
        ScriptedCollector c = new ScriptedCollector("X")
                .then(of(outage("1", "ESENLER", "a")).onCommit(commits::incrementAndGet))
                .then(new IllegalStateException("tablo bulunamadi"))
                .then(of(outage("1", "ESENLER", "a")));
        runner.run(c);
        runner.run(c);
        runner.run(c);
        assertThat(publisher.all()).extracting(OutageEvent::type).containsExactly(EventType.NEW);
        assertThat(snapshots.load(c.id())).containsOnlyKeys("TEST:1");
        assertThat(commits).hasValue(1);
    }

    @Test
    void kaynakDegismediyseDiffYapilmaz() {
        ScriptedCollector c = new ScriptedCollector("ISKI")
                .then(of(outage("1", "ADALAR", "a")))
                .then(CollectResult.unchangedSinceLastScan());
        runner.run(c);
        runner.run(c);
        assertThat(publisher.batches).hasSize(1);
        assertThat(snapshots.load(c.id())).hasSize(1);
        assertThat(metrics.lastSuccessEpochSeconds(c.id())).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void metrikIsimleriPrometheusIcinDogru() {
        metrics.register(new FeedId("BEDAS", "unplanned"));
        assertThat(registry.find("collector.last.success.timestamp").tag("source", "BEDAS").tag("feed", "unplanned")
                .gauge()).isNotNull();
        assertThat(registry.find("collector.errors").tag("source", "BEDAS").counter()).isNotNull();
        assertThat(registry.find("collector.events").tag("event", "GONE").counter()).isNotNull();
    }
}
