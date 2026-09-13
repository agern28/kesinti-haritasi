package tr.kesintiharitasi.api.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tr.kesintiharitasi.api.config.ApiProperties;

/**
 * outage-events stream'ini consumer group ile okur. Her pod ayni gruptaki ayri bir consumer; bir mesaj tek pod'a
 * gider. Mesaj veritabanina yazildiktan sonra onaylanir (XACK). Yazilamazsa onaylanmaz; claim-idle kadar bekledikten
 * sonra (pod olmus olabilir) bir pod onu devralir (XCLAIM) ve tekrar dener. Bozuk mesajlar onaylanip atlanir.
 */
public class OutageStreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutageStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final OutageEventProcessor processor;
    private final ApiProperties.Stream props;
    private final MeterRegistry registry;
    private final String consumerName;
    private volatile boolean running;
    private Thread thread;
    private Instant lastClaim = Instant.EPOCH;

    public OutageStreamConsumer(StringRedisTemplate redis, OutageEventProcessor processor, ApiProperties.Stream props,
                                MeterRegistry registry) {
        this.redis = redis;
        this.processor = processor;
        this.props = props;
        this.registry = registry;
        this.consumerName = props.consumerName();
    }

    public String consumerName() {
        return consumerName;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("outage-stream-" + consumerName).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(props.block().toMillis() + 2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        boolean ready = false;
        while (running) {
            try {
                if (!ready) {
                    ensureGroup();
                    // Bu consumer adina onceden alinmis ama onaylanmamis mesajlar (ornegin yeniden baslama)
                    handle(read(ReadOffset.from("0"), false));
                    ready = true;
                    log.info("outage-events tuketiliyor (grup {}, consumer {})", props.group(), consumerName);
                }
                if (Instant.now().isAfter(lastClaim.plus(props.claimInterval()))) {
                    claimStale();
                    lastClaim = Instant.now();
                }
                handle(read(ReadOffset.lastConsumed(), true));
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("stream okunamadi: {}", e.toString());
                sleep(1000);
            }
        }
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset, boolean block) {
        StreamReadOptions options = StreamReadOptions.empty().count(props.batchSize());
        if (block) {
            options = options.block(props.block());
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .read(Consumer.from(props.group(), consumerName), options, StreamOffset.create(props.key(), offset));
        return records == null ? List.of() : records;
    }

    private void handle(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> r : records) {
            Map<String, String> fields = new HashMap<>();
            r.getValue().forEach((k, v) -> fields.put(k.toString(), v.toString()));
            String event = fields.getOrDefault("event", "?");
            try {
                OutageEventProcessor.Result result = processor.process(fields);
                count(event, result == OutageEventProcessor.Result.APPLIED ? "applied" : "noop");
                ack(r.getId());
            } catch (BadEventException | JacksonException | DateTimeParseException e) {
                count(event, "bad");
                log.warn("bozuk olay atlandi {}: {}", r.getId(), e.toString());
                ack(r.getId());
            } catch (RuntimeException e) {
                // Veritabani/Redis gecici hatasi: onaylanmaz, claim ile tekrar denenir.
                count(event, "error");
                log.warn("olay islenemedi {}: {}", r.getId(), e.toString());
            }
        }
    }

    /** Baska consumer'larda (olmus pod'lar) uzun suredir onaylanmamis mesajlari devralir. */
    private void claimStale() {
        PendingMessages pending = redis.opsForStream().pending(props.key(), props.group(), Range.unbounded(), 100);
        List<RecordId> stale = pending.stream()
                .filter(p -> !p.getConsumerName().equals(consumerName))
                .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(props.claimIdle()) >= 0)
                .map(PendingMessage::getId)
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream()
                .claim(props.key(), props.group(), consumerName, props.claimIdle(), stale.toArray(RecordId[]::new));
        log.info("{} bekleyen olay devralindi", claimed.size());
        handle(claimed);
    }

    private void ensureGroup() {
        try {
            redis.execute((RedisCallback<String>) (RedisConnection c) -> c.streamCommands().xGroupCreate(
                    props.key().getBytes(StandardCharsets.UTF_8), props.group(), ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            String msg = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            if (!msg.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void ack(RecordId id) {
        redis.opsForStream().acknowledge(props.key(), props.group(), id);
    }

    private void count(String event, String result) {
        registry.counter("api.stream.events", "event", event, "result", result).increment();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
