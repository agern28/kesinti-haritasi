package tr.kesintiharitasi.collector.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageKeys;

/**
 * Onceki snapshot ile guncel tarama arasindaki fark: NEW / UPDATED / GONE.
 * Degisiklik yoksa olay listesi bos doner ve stream'e hicbir sey yazilmaz.
 */
public class Differ {

    public record DiffResult(List<OutageEvent> events, Map<String, String> snapshot, int duplicates) {

        public Map<EventType, Integer> counts() {
            Map<EventType, Integer> c = new EnumMap<>(EventType.class);
            for (EventType t : EventType.values()) {
                c.put(t, 0);
            }
            events.forEach(e -> c.merge(e.type(), 1, Integer::sum));
            return c;
        }
    }

    public DiffResult diff(FeedId feed, Map<String, String> previous, List<Outage> current, Instant scannedAt) {
        Map<String, Outage> byKey = new LinkedHashMap<>();
        int duplicates = 0;
        for (Outage o : current) {
            // Ayni kaydi iki kez veren kaynaklar var (KCETAŞ'ta ayni mahalle/saat icin iki trafo); ilki kalir.
            if (byKey.putIfAbsent(OutageKeys.dedupKey(o), o) != null) {
                duplicates++;
            }
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        List<OutageEvent> events = new ArrayList<>();
        byKey.forEach((key, outage) -> {
            String hash = OutageKeys.contentHash(outage);
            snapshot.put(key, hash);
            String before = previous.get(key);
            if (before == null) {
                events.add(new OutageEvent(EventType.NEW, feed, key, hash, outage, scannedAt));
            } else if (!before.equals(hash)) {
                events.add(new OutageEvent(EventType.UPDATED, feed, key, hash, outage, scannedAt));
            }
        });
        previous.forEach((key, hash) -> {
            if (!snapshot.containsKey(key)) {
                events.add(new OutageEvent(EventType.GONE, feed, key, hash, null, scannedAt));
            }
        });
        return new DiffResult(events, snapshot, duplicates);
    }
}
