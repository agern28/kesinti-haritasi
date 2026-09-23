package tr.kesintiharitasi.collector.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tr.kesintiharitasi.collector.model.FeedId;

@ConfigurationProperties("collector")
public record CollectorProperties(
        @DefaultValue("KesintiHaritasi/0.1 (+https://github.com/agern28/kesinti-haritasi)") String userAgent,
        @DefaultValue Scheduling scheduling,
        @DefaultValue Http http,
        @DefaultValue Robots robots,
        @DefaultValue Stream stream,
        @DefaultValue Sources sources,
        Map<String, Feed> feeds) {

    public record Scheduling(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration jitterMax,
            @DefaultValue("30s") Duration initialDelayMax) {
    }

    public record Http(
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("60s") Duration requestTimeout,
            @DefaultValue("2s") Duration minHostDelay) {
    }

    public record Robots(
            @DefaultValue("24h") Duration cacheTtl,
            @DefaultValue("10m") Duration failureTtl) {
    }

    /** maxLen: Redis bellek sinirina gore; olcum olay basina ~0,56 KB, 30 bin olay ~17 MB (application.yml). */
    public record Stream(
            @DefaultValue("outage-events") String key,
            @DefaultValue("30000") long maxLen) {
    }

    public record Sources(
            @DefaultValue("40") int ckLocationLookupsPerScan,
            @DefaultValue("2") int kcetasDaysAhead) {
    }

    /** interval bossa collector'in varsayilan araligi kullanilir. */
    public record Feed(@DefaultValue("true") boolean enabled, Duration interval) {
    }

    public Feed feed(FeedId id) {
        Feed f = feeds == null ? null : feeds.get(id.key());
        return f == null ? new Feed(true, null) : f;
    }
}
