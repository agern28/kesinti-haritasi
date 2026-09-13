package tr.kesintiharitasi.api.live;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.api.config.ApiProperties;

/**
 * Bu pod'a bagli SSE istemcileri.
 * <ul>
 *   <li>Canli olaylar Redis Pub/Sub'dan gelir (onMessage) ve tum istemcilere gider.</li>
 *   <li>Last-Event-ID ile baglanan istemciye once kacirdigi olaylar sse-events stream'inden gonderilir.
 *       Istemci cok eskide kalmissa (olaylar kirpilmis) outage.resync gider; istemci listeyi bastan yukler.</li>
 *   <li>Her istemci en son aldigi id'yi tutar; ayni olay iki kez ya da geri sirada gitmez.</li>
 *   <li>Periyodik heartbeat (SSE yorum satiri) hem proxy'lerin baglantiyi kesmemesi hem de olu istemcileri
 *       temizlemek icin.</li>
 * </ul>
 */
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private static final class Client {
        final SseEmitter emitter;
        String lastId;

        Client(SseEmitter emitter, String lastId) {
            this.emitter = emitter;
            this.lastId = lastId;
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final ApiProperties.Sse props;
    private final Counter sent;
    private final Timer delivery;

    public SseHub(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties.Sse props, MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.props = props;
        Gauge.builder("api.sse.clients", clients, Set::size).description("Bu pod'a bagli SSE istemcisi").register(registry);
        this.sent = Counter.builder("api.sse.events.sent").description("Istemcilere gonderilen olay").register(registry);
        this.delivery = Timer.builder("api.sse.delivery")
                .description("Olayin veritabanina yazilmasindan istemciye gonderilmesine kadar gecen sure")
                .publishPercentileHistogram().register(registry);
    }

    public SseEmitter connect(String lastEventId) {
        SseEmitter emitter = new SseEmitter(props.timeout().toMillis());
        String last = StreamIds.valid(lastEventId) ? lastEventId : null;
        Client client = new Client(emitter, last);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> {
            clients.remove(client);
            emitter.complete();
        });
        emitter.onError(e -> clients.remove(client));
        synchronized (client) {
            // Once kaydol, sonra kacirilanlari gonder: arada gelen canli olaylar bu kilidi bekler ve
            // tekrar/geri sira kontrolunden gecer.
            clients.add(client);
            try {
                emitter.send(SseEmitter.event().comment("bagli").reconnectTime(props.retry().toMillis()));
                if (last != null) {
                    replay(client, last);
                }
            } catch (IOException | IllegalStateException e) {
                clients.remove(client);
            }
        }
        return emitter;
    }

    private void replay(Client client, String lastId) throws IOException {
        List<MapRecord<String, Object, Object>> oldest = redis.opsForStream()
                .range(props.eventsKey(), Range.unbounded(), Limit.limit().count(1));
        if (!oldest.isEmpty() && StreamIds.compare(lastId, oldest.get(0).getId().getValue()) < 0) {
            // Istemcinin son gordugu olay artik tutulmuyor: arada kacirdiklari bilinmiyor.
            client.emitter.send(SseEmitter.event().name(LiveEvents.RESYNC).data("{}", MediaType.APPLICATION_JSON));
        }
        List<MapRecord<String, Object, Object>> missed = redis.opsForStream()
                .range(props.eventsKey(), Range.closed(lastId, "+"), Limit.limit().count(props.replayLimit()));
        for (MapRecord<String, Object, Object> r : missed) {
            Map<Object, Object> v = r.getValue();
            sendTo(client, r.getId().getValue(), String.valueOf(v.get("type")), String.valueOf(v.get("data")),
                    Instant.parse(String.valueOf(v.get("at"))));
        }
    }

    /** Redis Pub/Sub dinleyicisi cagirir. */
    public void onMessage(String message) {
        JsonNode m = json.readTree(message);
        String id = m.path("id").asString();
        String type = m.path("type").asString();
        String data = m.path("data").asString();
        Instant at = Instant.parse(m.path("at").asString());
        for (Client c : clients) {
            synchronized (c) {
                try {
                    sendTo(c, id, type, data, at);
                } catch (IOException | IllegalStateException e) {
                    drop(c);
                }
            }
        }
    }

    private void sendTo(Client c, String id, String type, String data, Instant at) throws IOException {
        if (StreamIds.compare(id, c.lastId) <= 0) {
            return;
        }
        c.emitter.send(SseEmitter.event().id(id).name(type).data(data, MediaType.APPLICATION_JSON));
        c.lastId = id;
        sent.increment();
        delivery.record(Duration.between(at, clock.instant()));
    }

    public void heartbeat() {
        for (Client c : clients) {
            synchronized (c) {
                try {
                    c.emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    drop(c);
                }
            }
        }
    }

    private void drop(Client c) {
        clients.remove(c);
        try {
            c.emitter.complete();
        } catch (RuntimeException e) {
            log.debug("emitter kapatilamadi: {}", e.toString());
        }
    }

    public int clientCount() {
        return clients.size();
    }
}
