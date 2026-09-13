package tr.kesintiharitasi.api.summary;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.api.outage.OutageRepository;

/**
 * Harita ozeti Redis'te: api:summary hash'i (alan "IL_KEY|ILCE_KEY", deger JSON) ve api:summary:ready bayragi.
 * Bayrak yoksa (ilk istek ya da TTL doldu) ozet veritabanindan bastan hesaplanir. Bir kesinti degisince sadece
 * o ilcenin alani yeniden hesaplanir. TTL, zamanla baslayip biten kesintilerin kacirilmasina karsi emniyet.
 */
public class SummaryCache {

    static final String HASH = "api:summary";
    static final String READY = "api:summary:ready";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final OutageRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;

    public SummaryCache(StringRedisTemplate redis, JsonMapper json, OutageRepository repository, Clock clock,
                        Duration ttl, MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
        this.hits = Counter.builder("api.summary.cache").tag("result", "hit")
                .description("Harita ozeti cache'ten geldi").register(registry);
        this.misses = Counter.builder("api.summary.cache").tag("result", "miss")
                .description("Harita ozeti veritabanindan hesaplandi").register(registry);
    }

    public List<DistrictSummary> all() {
        if (Boolean.TRUE.equals(redis.hasKey(READY))) {
            Map<Object, Object> entries = redis.opsForHash().entries(HASH);
            hits.increment();
            return entries.values().stream()
                    .map(v -> json.readValue(v.toString(), DistrictSummary.class))
                    .sorted(Comparator.comparing(DistrictSummary::ilKey).thenComparing(DistrictSummary::ilceKey))
                    .toList();
        }
        misses.increment();
        return rebuild();
    }

    public List<DistrictSummary> rebuild() {
        List<DistrictSummary> all = repository.summary(clock.instant());
        Map<String, String> fields = new LinkedHashMap<>();
        all.forEach(s -> fields.put(s.field(), json.writeValueAsString(s)));
        if (fields.isEmpty()) {
            redis.delete(HASH);
        } else {
            String tmp = HASH + ":tmp";
            redis.delete(tmp);
            redis.opsForHash().putAll(tmp, fields);
            redis.rename(tmp, HASH);
        }
        redis.opsForValue().set(READY, "1", ttl);
        return all;
    }

    /** Degisen ilcenin ozetini gunceller. Cache hic kurulmamissa bir sey yapmaz; ilk istek kurar. */
    public void refresh(String ilKey, String ilceKey) {
        if (!Boolean.TRUE.equals(redis.hasKey(READY))) {
            return;
        }
        String field = ilKey + "|" + ilceKey;
        Optional<DistrictSummary> s = repository.summaryFor(ilKey, ilceKey, clock.instant());
        if (s.isPresent()) {
            redis.opsForHash().put(HASH, field, json.writeValueAsString(s.get()));
        } else {
            redis.opsForHash().delete(HASH, field);
        }
    }
}
