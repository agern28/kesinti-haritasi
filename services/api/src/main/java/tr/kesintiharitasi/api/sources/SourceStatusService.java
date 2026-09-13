package tr.kesintiharitasi.api.sources;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.api.config.ApiProperties;

/**
 * Kaynaklarin son tarama durumu. Collector her taramadan sonra Redis'te collector:status hash'ine yazar
 * (alan "KAYNAK/feed"); burada okunur ve kaynak bazinda toplanir.
 * Bir feed "gecikmis" (stale): hic basarili taramasi yoksa ya da son basarili taramadan bu yana
 * aralik x staleFactor + staleGrace gecmisse.
 */
public class SourceStatusService {

    static final String KEY = "collector:status";

    /** Kaynak kimligi, gosterilecek ad, tur, bolge. */
    public record CatalogEntry(String source, String name, String type, String region) {
    }

    public static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry("BEDAS", "BEDAŞ", "ELECTRICITY", "İstanbul Avrupa yakası"),
            new CatalogEntry("AEDAS", "AEDAŞ", "ELECTRICITY", "Antalya, Burdur, Isparta"),
            new CatalogEntry("CEDAS", "ÇEDAŞ", "ELECTRICITY", "Sivas, Tokat, Yozgat"),
            new CatalogEntry("KCETAS", "KCETAŞ", "ELECTRICITY", "Kayseri"),
            new CatalogEntry("IZSU", "İZSU", "WATER", "İzmir"),
            new CatalogEntry("ISKI", "İSKİ (İBB Açık Veri)", "WATER", "İstanbul, geçmiş veri"));

    public record FeedStatus(String feed, Long intervalSeconds, Instant lastSuccessAt, Integer lastItems,
                             Instant lastErrorAt, String lastError, boolean stale) {
    }

    public record SourceStatus(String source, String name, String type, String region, Instant lastSuccessAt,
                               boolean stale, List<FeedStatus> feeds) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final ApiProperties.Sources props;

    public SourceStatusService(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties.Sources props) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    public List<SourceStatus> list() {
        Instant now = clock.instant();
        Map<String, List<FeedStatus>> feedsBySource = new LinkedHashMap<>();
        for (Object raw : redis.opsForHash().entries(KEY).values()) {
            JsonNode n = json.readTree(raw.toString());
            String source = n.path("source").asString();
            Long interval = n.has("intervalSeconds") ? n.path("intervalSeconds").asLong() : null;
            Instant lastSuccess = instant(n, "lastSuccessAt");
            feedsBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(new FeedStatus(
                    n.path("feed").asString(), interval, lastSuccess,
                    n.has("lastItems") ? n.path("lastItems").asInt() : null,
                    instant(n, "lastErrorAt"), n.has("lastError") ? n.path("lastError").asString() : null,
                    stale(lastSuccess, interval, now)));
        }
        List<SourceStatus> out = new ArrayList<>();
        for (CatalogEntry c : CATALOG) {
            out.add(toStatus(c, feedsBySource.remove(c.source())));
        }
        feedsBySource.forEach((source, feeds) -> out.add(toStatus(new CatalogEntry(source, source, null, null), feeds)));
        return out;
    }

    private SourceStatus toStatus(CatalogEntry c, List<FeedStatus> feeds) {
        List<FeedStatus> sorted = feeds == null ? List.of()
                : feeds.stream().sorted(Comparator.comparing(FeedStatus::feed)).toList();
        Instant lastSuccess = sorted.stream().map(FeedStatus::lastSuccessAt).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean stale = sorted.isEmpty() || sorted.stream().anyMatch(FeedStatus::stale);
        return new SourceStatus(c.source(), c.name(), c.type(), c.region(), lastSuccess, stale, sorted);
    }

    boolean stale(Instant lastSuccess, Long intervalSeconds, Instant now) {
        if (lastSuccess == null) {
            return true;
        }
        long interval = intervalSeconds == null ? 3600 : intervalSeconds;
        Duration allowed = Duration.ofSeconds((long) (interval * props.staleFactor())).plus(props.staleGrace());
        return now.isAfter(lastSuccess.plus(allowed));
    }

    private static Instant instant(JsonNode n, String field) {
        return n.has(field) && !n.path(field).asString().isBlank() ? Instant.parse(n.path(field).asString()) : null;
    }
}
