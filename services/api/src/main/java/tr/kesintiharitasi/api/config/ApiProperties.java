package tr.kesintiharitasi.api.config;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("api")
public record ApiProperties(
        @DefaultValue Stream stream,
        @DefaultValue Sse sse,
        @DefaultValue Summary summary,
        @DefaultValue Sweeper sweeper,
        @DefaultValue Sources sources) {

    public record Stream(
            @DefaultValue("outage-events") String key,
            @DefaultValue("api") String group,
            String consumer,
            @DefaultValue("100") int batchSize,
            @DefaultValue("2s") Duration block,
            @DefaultValue("60s") Duration claimIdle,
            @DefaultValue("30s") Duration claimInterval) {

        /** Consumer adi: config, yoksa HOSTNAME (Kubernetes'te pod adi), o da yoksa rastgele. */
        public String consumerName() {
            if (consumer != null && !consumer.isBlank()) {
                return consumer;
            }
            String host = System.getenv("HOSTNAME");
            return host != null && !host.isBlank() ? host : "api-" + UUID.randomUUID();
        }
    }

    public record Sse(
            @DefaultValue("sse-events") String eventsKey,
            @DefaultValue("outage-updates") String channel,
            @DefaultValue("10000") long replayMaxLen,
            @DefaultValue("1000") int replayLimit,
            @DefaultValue("15s") Duration heartbeat,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue("3s") Duration retry) {
    }

    public record Summary(@DefaultValue("10m") Duration ttl) {
    }

    public record Sweeper(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration interval,
            @DefaultValue("24h") Duration maxWindow) {
    }

    public record Sources(
            @DefaultValue("2") double staleFactor,
            @DefaultValue("60s") Duration staleGrace) {
    }
}
