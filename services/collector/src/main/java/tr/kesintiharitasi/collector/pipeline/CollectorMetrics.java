package tr.kesintiharitasi.collector.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import tr.kesintiharitasi.collector.model.FeedId;

/**
 * Prometheus metrikleri. Plan'daki uc metrik {source} etiketiyle; ek olarak {feed} etiketi var cunku
 * Faz 8'deki alarm ariza (30 dk) ve planli (3 saat) sayfalari ayri esiklerle izliyor.
 * <ul>
 *   <li>collector_last_success_timestamp{source,feed}: son basarili taramanin unix zamani (sn)</li>
 *   <li>collector_items_total{source,feed}: taramalarda bulunan kayit sayisi (kumulatif sayac)</li>
 *   <li>collector_errors_total{source,feed}: basarisiz tarama sayisi</li>
 *   <li>collector_items_last_scan{source,feed}: son taramada bulunan kayit sayisi (panel icin)</li>
 *   <li>collector_events_total{source,feed,event}: stream'e yazilan NEW/UPDATED/GONE</li>
 *   <li>collector_scan_duration_seconds{source,feed}</li>
 * </ul>
 */
public class CollectorMetrics {

    private record FeedMeters(Counter items, Counter errors, AtomicLong lastSuccess, AtomicLong lastItems,
                              Timer duration, Map<EventType, Counter> events) {
    }

    private final MeterRegistry registry;
    private final Map<FeedId, FeedMeters> meters = new ConcurrentHashMap<>();

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Uygulama acilirken cagrilir; seriler ilk taramadan once de gorunur. */
    public void register(FeedId feed) {
        meters.computeIfAbsent(feed, this::create);
    }

    public void success(FeedId feed, Integer items, Map<EventType, Integer> events, Duration duration, Instant at) {
        FeedMeters m = meters.computeIfAbsent(feed, this::create);
        if (items != null) {
            m.items().increment(items);
            m.lastItems().set(items);
        }
        events.forEach((type, count) -> m.events().get(type).increment(count));
        m.lastSuccess().set(at.getEpochSecond());
        m.duration().record(duration);
    }

    public void failure(FeedId feed, Duration duration) {
        FeedMeters m = meters.computeIfAbsent(feed, this::create);
        m.errors().increment();
        m.duration().record(duration);
    }

    public long lastSuccessEpochSeconds(FeedId feed) {
        FeedMeters m = meters.get(feed);
        return m == null ? 0 : m.lastSuccess().get();
    }

    public double errorCount(FeedId feed) {
        FeedMeters m = meters.get(feed);
        return m == null ? 0 : m.errors().count();
    }

    private FeedMeters create(FeedId feed) {
        Tags tags = Tags.of("source", feed.source(), "feed", feed.feed());
        AtomicLong lastSuccess = new AtomicLong();
        AtomicLong lastItems = new AtomicLong();
        Gauge.builder("collector.last.success.timestamp", lastSuccess, AtomicLong::get)
                .description("Son basarili taramanin unix zamani (saniye)").tags(tags).register(registry);
        Gauge.builder("collector.items.last.scan", lastItems, AtomicLong::get)
                .description("Son taramada bulunan kayit sayisi").tags(tags).register(registry);
        Map<EventType, Counter> events = new EnumMap<>(EventType.class);
        for (EventType t : EventType.values()) {
            events.put(t, Counter.builder("collector.events").description("Stream'e yazilan olaylar")
                    .tags(tags).tag("event", t.name()).register(registry));
        }
        return new FeedMeters(
                Counter.builder("collector.items").description("Taramalarda bulunan kayit sayisi")
                        .tags(tags).register(registry),
                Counter.builder("collector.errors").description("Basarisiz tarama sayisi")
                        .tags(tags).register(registry),
                lastSuccess, lastItems,
                Timer.builder("collector.scan.duration").description("Tarama suresi").tags(tags).register(registry),
                events);
    }
}
