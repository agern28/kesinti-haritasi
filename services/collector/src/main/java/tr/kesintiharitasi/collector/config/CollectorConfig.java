package tr.kesintiharitasi.collector.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.pipeline.CollectorMetrics;
import tr.kesintiharitasi.collector.pipeline.EventPublisher;
import tr.kesintiharitasi.collector.pipeline.RedisStores;
import tr.kesintiharitasi.collector.pipeline.ScanRunner;
import tr.kesintiharitasi.collector.pipeline.ScanScheduler;
import tr.kesintiharitasi.collector.pipeline.SnapshotStore;
import tr.kesintiharitasi.collector.pipeline.SourceStatusStore;
import tr.kesintiharitasi.collector.pipeline.StateStore;
import tr.kesintiharitasi.collector.source.SourceCollector;
import tr.kesintiharitasi.collector.source.ck.CkCompany;
import tr.kesintiharitasi.collector.source.ck.CkPlannedCollector;
import tr.kesintiharitasi.collector.source.ck.CkUnplannedCollector;
import tr.kesintiharitasi.collector.source.iski.IbbIskiCollector;
import tr.kesintiharitasi.collector.source.izsu.IzsuCollector;
import tr.kesintiharitasi.collector.source.kcetas.KcetasCollector;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    PoliteHttpClient politeHttpClient(CollectorProperties props, Clock clock) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new PoliteHttpClient(http, props.userAgent(), props.http().minHostDelay(),
                props.http().requestTimeout(), props.robots().cacheTtl(), props.robots().failureTtl(), clock);
    }

    @Bean
    SnapshotStore snapshotStore(StringRedisTemplate redis) {
        return new RedisStores.Snapshots(redis);
    }

    @Bean
    StateStore stateStore(StringRedisTemplate redis) {
        return new RedisStores.State(redis);
    }

    @Bean
    EventPublisher eventPublisher(StringRedisTemplate redis, JsonMapper json, CollectorProperties props) {
        return new RedisStores.StreamPublisher(redis, json, props.stream().key(), props.stream().maxLen());
    }

    @Bean
    SourceStatusStore sourceStatusStore(StringRedisTemplate redis, JsonMapper json) {
        return new RedisStores.Status(redis, json);
    }

    @Bean
    CollectorMetrics collectorMetrics(MeterRegistry registry) {
        return new CollectorMetrics(registry);
    }

    @Bean
    ScanRunner scanRunner(SnapshotStore snapshots, EventPublisher publisher, CollectorMetrics metrics, Clock clock,
                          CollectorProperties props, SourceStatusStore status) {
        return new ScanRunner(snapshots, publisher, metrics, clock, props.scheduling().jitterMax(),
                d -> Thread.sleep(d.toMillis()), status);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler scanTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(6);
        s.setThreadNamePrefix("scan-");
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.initialize();
        return s;
    }

    @Bean
    ScanScheduler scanScheduler(List<SourceCollector> collectors, ScanRunner runner, CollectorMetrics metrics,
                                ThreadPoolTaskScheduler scanTaskScheduler, CollectorProperties props, Clock clock,
                                SourceStatusStore status) {
        return new ScanScheduler(collectors, runner, metrics, scanTaskScheduler, props, clock, status);
    }

    // --- Kaynaklar ---

    @Bean
    SourceCollector bedasPlanned(PoliteHttpClient http, JsonMapper json) {
        return new CkPlannedCollector(CkCompany.BEDAS, http, json);
    }

    @Bean
    SourceCollector bedasUnplanned(PoliteHttpClient http, JsonMapper json, StateStore state, CollectorProperties p) {
        return new CkUnplannedCollector(CkCompany.BEDAS, http, json, state, p.sources().ckLocationLookupsPerScan());
    }

    @Bean
    SourceCollector aedasPlanned(PoliteHttpClient http, JsonMapper json) {
        return new CkPlannedCollector(CkCompany.AEDAS, http, json);
    }

    @Bean
    SourceCollector aedasUnplanned(PoliteHttpClient http, JsonMapper json, StateStore state, CollectorProperties p) {
        return new CkUnplannedCollector(CkCompany.AEDAS, http, json, state, p.sources().ckLocationLookupsPerScan());
    }

    @Bean
    SourceCollector cedasPlanned(PoliteHttpClient http, JsonMapper json) {
        return new CkPlannedCollector(CkCompany.CEDAS, http, json);
    }

    @Bean
    SourceCollector cedasUnplanned(PoliteHttpClient http, JsonMapper json, StateStore state, CollectorProperties p) {
        return new CkUnplannedCollector(CkCompany.CEDAS, http, json, state, p.sources().ckLocationLookupsPerScan());
    }

    @Bean
    SourceCollector kcetasPlanned(PoliteHttpClient http, JsonMapper json, Clock clock, CollectorProperties p) {
        return new KcetasCollector(http, json, clock, p.sources().kcetasDaysAhead());
    }

    @Bean
    SourceCollector izsu(PoliteHttpClient http) {
        return new IzsuCollector(http);
    }

    @Bean
    SourceCollector iskiDaily(PoliteHttpClient http, SnapshotStore snapshots, StateStore state) {
        return new IbbIskiCollector(http, snapshots, state);
    }
}
