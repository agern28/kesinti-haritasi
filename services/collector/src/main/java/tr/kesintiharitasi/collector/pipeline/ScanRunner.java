package tr.kesintiharitasi.collector.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/**
 * Tek bir feed'in tek taramasi: jitter -> collect -> diff -> stream'e yaz -> snapshot'i kaydet.
 * Hata yakalanir ve sayilir; digerlerini etkilemez, snapshot degismez (GONE uretilmez).
 */
public class ScanRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final SnapshotStore snapshots;
    private final EventPublisher publisher;
    private final CollectorMetrics metrics;
    private final Clock clock;
    private final Duration jitterMax;
    private final Sleeper sleeper;
    private final SourceStatusStore status;
    private final Differ differ = new Differ();

    public ScanRunner(SnapshotStore snapshots, EventPublisher publisher, CollectorMetrics metrics, Clock clock,
                      Duration jitterMax, Sleeper sleeper) {
        this(snapshots, publisher, metrics, clock, jitterMax, sleeper, SourceStatusStore.NOOP);
    }

    public ScanRunner(SnapshotStore snapshots, EventPublisher publisher, CollectorMetrics metrics, Clock clock,
                      Duration jitterMax, Sleeper sleeper, SourceStatusStore status) {
        this.snapshots = snapshots;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
        this.jitterMax = jitterMax;
        this.sleeper = sleeper;
        this.status = status;
    }

    /** @return tarama basarili mi */
    public boolean run(SourceCollector collector) {
        FeedId feed = collector.id();
        long t0 = System.nanoTime();
        try {
            sleepJitter();
            CollectResult result = collector.collect();
            Instant now = clock.instant();
            if (result.unchanged()) {
                metrics.success(feed, null, Map.of(), elapsed(t0), now);
                recordStatus(() -> status.succeeded(feed, now, null));
                log.info("{}: kaynak degismemis, diff yapilmadi", feed);
                return true;
            }
            Differ.DiffResult diff = differ.diff(feed, snapshots.load(feed), result.outages(), now);
            if (!diff.events().isEmpty()) {
                publisher.publish(diff.events());
                snapshots.save(feed, diff.snapshot());
            }
            result.afterCommit().run();
            Map<EventType, Integer> counts = diff.counts();
            metrics.success(feed, result.outages().size(), counts, elapsed(t0), now);
            recordStatus(() -> status.succeeded(feed, now, result.outages().size()));
            log.info("{}: {} kayit, NEW={} UPDATED={} GONE={}{} ({} ms)", feed, result.outages().size(),
                    counts.get(EventType.NEW), counts.get(EventType.UPDATED), counts.get(EventType.GONE),
                    diff.duplicates() > 0 ? ", tekrar eden " + diff.duplicates() : "", elapsed(t0).toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            metrics.failure(feed, elapsed(t0));
            recordStatus(() -> status.failed(feed, clock.instant(), e.toString()));
            log.warn("{}: tarama basarisiz: {}", feed, e.toString());
            return false;
        }
    }

    /** Durum yazilamazsa (Redis gecici olarak yok) tarama sonucu degismez. */
    private void recordStatus(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.debug("kaynak durumu yazilamadi: {}", e.toString());
        }
    }

    private void sleepJitter() throws InterruptedException {
        long max = jitterMax.toMillis();
        if (max > 0) {
            sleeper.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(max + 1)));
        }
    }

    private static Duration elapsed(long t0) {
        return Duration.ofNanos(System.nanoTime() - t0);
    }
}
