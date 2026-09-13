package tr.kesintiharitasi.api.live;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.kesintiharitasi.api.config.ApiProperties;
import tr.kesintiharitasi.api.outage.Outage;

/**
 * Tarayiciya gidecek olayi yayinlar:
 * 1. sse-events stream'ine yazar (kimlik = stream id; yeniden baglanan istemci Last-Event-ID ile buradan okur),
 * 2. ayni olayi Redis Pub/Sub kanalina gonderir; her api pod'u kendi bagli istemcilerine iletir.
 * Olayi isleyen pod da kendi istemcilerine Pub/Sub uzerinden iletir, dogrudan degil; iki kez gitmez.
 */
public class LiveEvents {

    public static final String CREATED = "outage.created";
    public static final String UPDATED = "outage.updated";
    public static final String ENDED = "outage.ended";
    public static final String RESYNC = "outage.resync";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final ApiProperties.Sse props;

    public LiveEvents(StringRedisTemplate redis, JsonMapper json, Clock clock, ApiProperties.Sse props) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    /** @return olay kimligi (sse-events stream id'si) */
    public String publish(String type, Outage outage) {
        Instant at = clock.instant();
        String data = json.writeValueAsString(outage);
        RecordId id = redis.opsForStream().add(StreamRecords.string(Map.of("type", type, "data", data, "at", at.toString()))
                .withStreamKey(props.eventsKey()));
        redis.opsForStream().trim(props.eventsKey(), props.replayMaxLen(), true);
        ObjectNode msg = json.createObjectNode();
        msg.put("id", id.getValue());
        msg.put("type", type);
        msg.put("at", at.toString());
        msg.put("data", data);
        redis.convertAndSend(props.channel(), json.writeValueAsString(msg));
        return id.getValue();
    }
}
