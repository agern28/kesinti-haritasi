package tr.kesintiharitasi.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.api.ingest.OutageEventProcessor;
import tr.kesintiharitasi.api.ingest.OutageStreamConsumer;
import tr.kesintiharitasi.api.live.LiveEvents;
import tr.kesintiharitasi.api.live.SseHub;
import tr.kesintiharitasi.api.live.TransitionSweeper;
import tr.kesintiharitasi.api.outage.OutageRepository;
import tr.kesintiharitasi.api.sources.SourceStatusService;
import tr.kesintiharitasi.api.summary.SummaryCache;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiProperties.class)
public class ApiConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OutageRepository outageRepository(JdbcClient jdbc, Clock clock) {
        return new OutageRepository(jdbc, clock);
    }

    @Bean
    SummaryCache summaryCache(StringRedisTemplate redis, JsonMapper json, OutageRepository repository, Clock clock,
                              ApiProperties props, MeterRegistry registry) {
        return new SummaryCache(redis, json, repository, clock, props.summary().ttl(), registry);
    }

    @Bean
    LiveEvents liveEvents(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties props) {
        return new LiveEvents(redis, json, clock, props.sse());
    }

    @Bean
    SseHub sseHub(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties props, MeterRegistry registry) {
        return new SseHub(redis, json, clock, props.sse(), registry);
    }

    /** Pub/Sub: her pod ayni kanali dinler ve olayi kendi bagli istemcilerine iletir. */
    @Bean
    RedisMessageListenerContainer outageUpdatesListener(RedisConnectionFactory factory, SseHub hub, ApiProperties props) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> hub.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(props.sse().channel()));
        return container;
    }

    @Bean
    OutageEventProcessor outageEventProcessor(OutageRepository repository, SummaryCache summary, LiveEvents live,
                                              JsonMapper json, Clock clock) {
        return new OutageEventProcessor(repository, summary, live, json, clock);
    }

    @Bean
    OutageStreamConsumer outageStreamConsumer(StringRedisTemplate redis, OutageEventProcessor processor,
                                              ApiProperties props, MeterRegistry registry) {
        return new OutageStreamConsumer(redis, processor, props.stream(), registry);
    }

    @Bean
    TransitionSweeper transitionSweeper(OutageRepository repository, SummaryCache summary, LiveEvents live,
                                        StringRedisTemplate redis, Clock clock, ApiProperties props) {
        return new TransitionSweeper(repository, summary, live, redis, clock, props.sweeper());
    }

    @Bean
    SourceStatusService sourceStatusService(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties props) {
        return new SourceStatusService(redis, json, clock, props.sources());
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler apiTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("api-sched-");
        s.initialize();
        return s;
    }

    /** SSE heartbeat ve zaman taramasi. */
    @Bean
    ApplicationRunner periodicTasks(ThreadPoolTaskScheduler apiTaskScheduler, SseHub hub, TransitionSweeper sweeper,
                                    ApiProperties props) {
        return args -> {
            apiTaskScheduler.scheduleAtFixedRate(hub::heartbeat, props.sse().heartbeat());
            if (props.sweeper().enabled()) {
                apiTaskScheduler.scheduleWithFixedDelay(sweeper::sweep, props.sweeper().interval());
            }
        };
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Kesinti Haritası API")
                .version("v1")
                .description("Elektrik ve su kesintileri. Canli olaylar: GET /api/stream (SSE), tarayicida /canli.html."));
    }
}
