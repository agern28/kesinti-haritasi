package tr.kesintiharitasi.collector.pipeline;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.model.FeedId;

/** Redis tabanli snapshot, durum ve stream yazicisi. */
public final class RedisStores {

    private RedisStores() {
    }

    public static class Snapshots implements SnapshotStore {

        private final StringRedisTemplate redis;

        public Snapshots(StringRedisTemplate redis) {
            this.redis = redis;
        }

        static String key(FeedId feed) {
            return "collector:snapshot:" + feed.source() + ":" + feed.feed();
        }

        @Override
        public Map<String, String> load(FeedId feed) {
            Map<Object, Object> raw = redis.opsForHash().entries(key(feed));
            Map<String, String> out = new HashMap<>(raw.size());
            raw.forEach((k, v) -> out.put(k.toString(), v.toString()));
            return out;
        }

        /** Once gecici anahtara yazilir, sonra RENAME: okuyan hic yarim snapshot gormez. */
        @Override
        public void save(FeedId feed, Map<String, String> snapshot) {
            String key = key(feed);
            if (snapshot.isEmpty()) {
                redis.delete(key);
                return;
            }
            String tmp = key + ":tmp";
            redis.delete(tmp);
            redis.opsForHash().putAll(tmp, snapshot);
            redis.rename(tmp, key);
        }

        @Override
        public boolean exists(FeedId feed) {
            return Boolean.TRUE.equals(redis.hasKey(key(feed)));
        }
    }

    public static class State implements StateStore {

        private final StringRedisTemplate redis;

        public State(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public String get(String key) {
            return redis.opsForValue().get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            if (ttl == null) {
                redis.opsForValue().set(key, value);
            } else {
                redis.opsForValue().set(key, value, ttl);
            }
        }
    }

    /** Feed durumlari: collector:status hash'i, alan "KAYNAK/feed", deger JSON. Her feed kendi alanini yazar. */
    public static class Status implements SourceStatusStore {

        static final String KEY = "collector:status";
        private static final int MAX_ERROR_LENGTH = 300;

        private final StringRedisTemplate redis;
        private final JsonMapper json;

        public Status(StringRedisTemplate redis, JsonMapper json) {
            this.redis = redis;
            this.json = json;
        }

        @Override
        public void registered(FeedId feed, Duration interval) {
            update(feed, n -> n.put("intervalSeconds", interval.toSeconds()));
        }

        @Override
        public void succeeded(FeedId feed, java.time.Instant at, Integer items) {
            update(feed, n -> {
                n.put("lastSuccessAt", at.toString());
                if (items != null) {
                    n.put("lastItems", items);
                }
            });
        }

        @Override
        public void failed(FeedId feed, java.time.Instant at, String error) {
            String e = error == null ? "" : error;
            update(feed, n -> {
                n.put("lastErrorAt", at.toString());
                n.put("lastError", e.length() > MAX_ERROR_LENGTH ? e.substring(0, MAX_ERROR_LENGTH) : e);
            });
        }

        private void update(FeedId feed, java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> change) {
            String field = feed.source() + "/" + feed.feed();
            Object raw = redis.opsForHash().get(KEY, field);
            tools.jackson.databind.node.ObjectNode node = raw == null
                    ? json.createObjectNode()
                    : (tools.jackson.databind.node.ObjectNode) json.readTree(raw.toString());
            node.put("source", feed.source());
            node.put("feed", feed.feed());
            change.accept(node);
            redis.opsForHash().put(KEY, field, json.writeValueAsString(node));
        }
    }

    /**
     * Olaylari Redis Stream'e yazar. Alanlar: event, source, feed, dedupKey, contentHash, scannedAt, payload (JSON).
     * Stream MAXLEN ~ ile kirpilir; api uzun sure kapali kalirsa en eski olaylar dusebilir, ama bir sonraki
     * taramada degisen her sey zaten yeniden gelir.
     */
    public static class StreamPublisher implements EventPublisher {

        private final StringRedisTemplate redis;
        private final JsonMapper json;
        private final String streamKey;
        private final long maxLen;

        public StreamPublisher(StringRedisTemplate redis, JsonMapper json, String streamKey, long maxLen) {
            this.redis = redis;
            this.json = json;
            this.streamKey = streamKey;
            this.maxLen = maxLen;
        }

        @Override
        public void publish(List<OutageEvent> events) {
            for (OutageEvent e : events) {
                Map<String, String> fields = new HashMap<>();
                fields.put("event", e.type().name());
                fields.put("source", e.feed().source());
                fields.put("feed", e.feed().feed());
                fields.put("dedupKey", e.dedupKey());
                fields.put("contentHash", e.contentHash());
                fields.put("scannedAt", e.scannedAt().toString());
                fields.put("payload", e.outage() == null ? "" : json.writeValueAsString(e.outage()));
                redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(streamKey));
            }
            if (maxLen > 0 && !events.isEmpty()) {
                redis.opsForStream().trim(streamKey, maxLen, true);
            }
        }
    }
}
