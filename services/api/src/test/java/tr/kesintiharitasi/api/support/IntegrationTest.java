package tr.kesintiharitasi.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uygulamanin tamami, gercek PostgreSQL ve Redis ile. Tum alt siniflar ayni Spring context'ini paylasir.
 * Testler birbirini etkilemesin diye her test kendi il/ilce/dedupKey degerlerini uretir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        Containers.register(registry);
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected StringRedisTemplate redis;

    // Iki container bean'i var: bizim kanalimizi dinleyen bu, bir de Boot'un otomatik kurdugu.
    @Autowired
    @Qualifier("outageUpdatesListener")
    protected RedisMessageListenerContainer pubsub;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    protected final HttpClient http = HttpClient.newHttpClient();

    /**
     * Pub/Sub aboneligi acilista asenkron kuruluyor ve Pub/Sub'in gecmisi yok: abone olmadan once
     * yayinlanan olay kayboluyor. 2026-09-23'te CI'da TransitionSweeperTest tam bu yarista
     * "beklenen olay gelmedi" diye kirilmisti. Uygulamada aynisi readiness'a bagli (sseFanout).
     */
    @BeforeEach
    void pubsubAbonesiHazirOlsun() throws InterruptedException {
        long bitis = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!pubsub.isListening() && System.nanoTime() < bitis) {
            Thread.sleep(50);
        }
        assertThat(pubsub.isListening()).as("Pub/Sub aboneligi kuruldu").isTrue();
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode getJson(String path) throws Exception {
        HttpResponse<String> res = get(path);
        assertThat(res.statusCode()).as(path).isEqualTo(200);
        return json.readTree(res.body());
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    /** Collector'in payload'i (collector'daki Outage record'unun JSON'u). */
    protected static Map<String, Object> payload(String il, String ilce, List<String> mahalleler, String type,
                                                 boolean planned, Instant start, Instant end) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("source", "TEST");
        p.put("externalId", null);
        p.put("type", type);
        p.put("planned", planned);
        p.put("il", il);
        p.put("ilce", ilce);
        p.put("mahalleler", mahalleler);
        p.put("startsAt", start.toString());
        p.put("endsAt", end == null ? null : end.toString());
        p.put("reason", "test");
        p.put("sourceUrl", "https://example.org/kesinti");
        p.put("lat", null);
        p.put("lon", null);
        return p;
    }

    /** Collector'in yazdigi bicimde outage-events stream'ine olay ekler. */
    protected void publish(String event, String dedupKey, String contentHash, Map<String, Object> payload,
                           Instant scannedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("source", "TEST");
        fields.put("feed", "planned");
        fields.put("dedupKey", dedupKey);
        fields.put("contentHash", contentHash);
        fields.put("scannedAt", scannedAt.toString());
        fields.put("payload", payload == null ? "" : json.writeValueAsString(payload));
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey("outage-events"));
    }

    protected long rowCount(String dedupKey) {
        return jdbc.sql("select count(*) from outage where dedup_key = :k").param("k", dedupKey).query(Long.class).single();
    }
}
