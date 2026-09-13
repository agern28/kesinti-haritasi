package tr.kesintiharitasi.collector.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;
import tr.kesintiharitasi.collector.support.Fixtures;
import tr.kesintiharitasi.collector.support.InMemoryStores;
import tr.kesintiharitasi.collector.support.RedisTestContainer;
import tr.kesintiharitasi.collector.support.TestOutages;

/** api'nin /api/sources icin okudugu collector:status hash'i. Gercek Redis ile. */
class SourceStatusTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(RedisTestContainer.host(), RedisTestContainer.port()));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private static SourceCollector collector(String source, Object result) {
        return new SourceCollector() {
            @Override
            public FeedId id() {
                return new FeedId(source, "planned");
            }

            @Override
            public Duration defaultInterval() {
                return Duration.ofMinutes(15);
            }

            @Override
            public CollectResult collect() throws Exception {
                if (result instanceof Exception e) {
                    throw e;
                }
                return (CollectResult) result;
            }
        };
    }

    private JsonNode status(String field) {
        return Fixtures.JSON.readTree(redis.opsForHash().get("collector:status", field).toString());
    }

    @Test
    void basariVeHataDurumuYazilir() {
        redis.delete("collector:status");
        RedisStores.Status status = new RedisStores.Status(redis, Fixtures.JSON);
        ScanRunner runner = new ScanRunner(new InMemoryStores.Snapshots(), new InMemoryStores.Publisher(),
                new CollectorMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO,
                d -> { }, status);

        status.registered(new FeedId("OK", "planned"), Duration.ofMinutes(15));
        runner.run(collector("OK", CollectResult.of(List.of(TestOutages.outage("1", "ESENLER", "a")))));
        runner.run(collector("BOZUK", new IOException("HTTP 503 https://example.org")));

        JsonNode ok = status("OK/planned");
        assertThat(ok.path("intervalSeconds").asLong()).isEqualTo(900);
        assertThat(ok.path("lastSuccessAt").asString()).isEqualTo("2026-09-13T10:00:00Z");
        assertThat(ok.path("lastItems").asInt()).isEqualTo(1);

        JsonNode bad = status("BOZUK/planned");
        assertThat(bad.path("lastSuccessAt").isMissingNode()).isTrue();
        assertThat(bad.path("lastError").asString()).contains("HTTP 503");
    }
}
