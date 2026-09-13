package tr.kesintiharitasi.collector.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.pipeline.EventPublisher;
import tr.kesintiharitasi.collector.pipeline.OutageEvent;
import tr.kesintiharitasi.collector.pipeline.SnapshotStore;
import tr.kesintiharitasi.collector.pipeline.StateStore;

public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static class Snapshots implements SnapshotStore {
        public final Map<FeedId, Map<String, String>> data = new HashMap<>();

        @Override
        public Map<String, String> load(FeedId feed) {
            return new HashMap<>(data.getOrDefault(feed, Map.of()));
        }

        @Override
        public void save(FeedId feed, Map<String, String> snapshot) {
            if (snapshot.isEmpty()) {
                data.remove(feed);
            } else {
                data.put(feed, new HashMap<>(snapshot));
            }
        }

        @Override
        public boolean exists(FeedId feed) {
            return data.containsKey(feed);
        }
    }

    public static class State implements StateStore {
        public final Map<String, String> data = new HashMap<>();

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            data.put(key, value);
        }
    }

    public static class Publisher implements EventPublisher {
        public final List<List<OutageEvent>> batches = new ArrayList<>();

        @Override
        public void publish(List<OutageEvent> events) {
            batches.add(List.copyOf(events));
        }

        public List<OutageEvent> all() {
            return batches.stream().flatMap(List::stream).toList();
        }
    }
}
