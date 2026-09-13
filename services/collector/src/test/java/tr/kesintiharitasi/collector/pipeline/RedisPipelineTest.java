package tr.kesintiharitasi.collector.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;
import tr.kesintiharitasi.collector.source.kcetas.KcetasParser;
import tr.kesintiharitasi.collector.support.Fixtures;
import tr.kesintiharitasi.collector.support.RedisTestContainer;

/**
 * Gercek Redis ile (Testcontainers): KCETAŞ fixture'i iki kez taraniyor.
 * Ilk tarama stream'e NEW yazar, ikincisi hicbir sey yazmaz; degisen kayit UPDATED, kaybolan GONE.
 */
class RedisPipelineTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

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

    private static final class ListCollector implements SourceCollector {
        List<Outage> outages;

        ListCollector(List<Outage> outages) {
            this.outages = outages;
        }

        @Override
        public FeedId id() {
            return new FeedId("KCETAS", "planned");
        }

        @Override
        public Duration defaultInterval() {
            return Duration.ofMinutes(15);
        }

        @Override
        public CollectResult collect() {
            return CollectResult.of(outages);
        }
    }

    @Test
    void ikinciTaramadaDegisiklikYoksaStreamBuyumez() {
        String stream = "test-events-" + UUID.randomUUID();
        redis.delete("collector:snapshot:KCETAS:planned");
        RedisStores.Snapshots snapshots = new RedisStores.Snapshots(redis);
        ScanRunner runner = new ScanRunner(snapshots, new RedisStores.StreamPublisher(redis, Fixtures.JSON, stream, 1000),
                new CollectorMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), Duration.ZERO, d -> { });
        List<Outage> outages = new KcetasParser().parse(Fixtures.json("kcetas/kesinti-sorgu-2026-09-11.json"));
        ListCollector collector = new ListCollector(outages);

        assertThat(runner.run(collector)).isTrue();
        assertThat(redis.opsForStream().size(stream)).isEqualTo(24);
        assertThat(snapshots.load(collector.id())).hasSize(24);

        assertThat(runner.run(collector)).isTrue();
        assertThat(redis.opsForStream().size(stream)).as("degisiklik yok, olay yok").isEqualTo(24);

        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(stream, Range.unbounded());
        Map<Object, Object> first = records.get(0).getValue();
        assertThat(first).containsEntry("event", "NEW").containsEntry("source", "KCETAS").containsEntry("feed", "planned");
        assertThat(first.get("payload").toString())
                .contains("\"ilce\":\"PINARBAŞI\"")
                .contains("\"startsAt\":\"2026-09-11T06:00:00Z\"");

        List<Outage> changed = new ArrayList<>(outages.subList(1, outages.size()));
        Outage second = changed.get(0);
        changed.set(0, new Outage(second.source(), second.externalId(), second.type(), second.planned(), second.il(),
                second.ilce(), second.mahalleler(), second.startsAt(), second.endsAt().plusSeconds(3600),
                second.reason(), second.sourceUrl(), second.lat(), second.lon()));
        collector.outages = changed;
        assertThat(runner.run(collector)).isTrue();

        List<MapRecord<String, Object, Object>> after = redis.opsForStream().range(stream, Range.unbounded());
        assertThat(after.subList(24, after.size())).extracting(r -> r.getValue().get("event"))
                .containsExactlyInAnyOrder("UPDATED", "GONE");
        assertThat(snapshots.load(collector.id())).hasSize(23);
    }
}
