package tr.kesintiharitasi.collector.pipeline;

import java.time.Instant;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;

/** Stream'e yazilan olay. GONE icin outage null; api dedupKey ile kaydi bulur. */
public record OutageEvent(EventType type, FeedId feed, String dedupKey, String contentHash, Outage outage,
                          Instant scannedAt) {
}
