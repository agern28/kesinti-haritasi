package tr.kesintiharitasi.collector.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import tr.kesintiharitasi.collector.config.CollectorProperties;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

class ScanSchedulerTest {

    private static SourceCollector collector(String source, String feed, Duration defaultInterval) {
        return new SourceCollector() {
            @Override
            public FeedId id() {
                return new FeedId(source, feed);
            }

            @Override
            public Duration defaultInterval() {
                return defaultInterval;
            }

            @Override
            public CollectResult collect() {
                return CollectResult.of(List.of());
            }
        };
    }

    private static CollectorProperties props(Map<String, CollectorProperties.Feed> feeds) {
        return new CollectorProperties("UA",
                new CollectorProperties.Scheduling(false, Duration.ZERO, Duration.ZERO),
                new CollectorProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO),
                new CollectorProperties.Robots(Duration.ofHours(1), Duration.ofMinutes(1)),
                new CollectorProperties.Stream("s", 10),
                new CollectorProperties.Sources(1, 1),
                feeds);
    }

    @Test
    void aralikConfigtenYoksaVarsayilanEnSik5Dakika() {
        SourceCollector planned = collector("A", "planned", Duration.ofMinutes(15));
        SourceCollector fast = collector("A", "unplanned", Duration.ofMinutes(5));
        SourceCollector off = collector("B", "planned", Duration.ofMinutes(15));
        CollectorMetrics metrics = new CollectorMetrics(new SimpleMeterRegistry());
        ScanScheduler s = new ScanScheduler(List.of(planned, fast, off), mock(ScanRunner.class), metrics,
                mock(TaskScheduler.class),
                props(Map.of("a-unplanned", new CollectorProperties.Feed(true, Duration.ofMinutes(1)),
                        "b-planned", new CollectorProperties.Feed(false, null))),
                Clock.systemUTC(), SourceStatusStore.NOOP);

        assertThat(s.enabledCollectors()).containsExactly(planned, fast);
        assertThat(s.interval(planned)).isEqualTo(Duration.ofMinutes(15));
        assertThat(s.interval(fast)).as("1 dakika istense de en sik 5 dakika").isEqualTo(Duration.ofMinutes(5));
    }
}
