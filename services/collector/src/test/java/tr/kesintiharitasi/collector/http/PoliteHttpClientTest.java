package tr.kesintiharitasi.collector.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Yerel bir HTTP sunucusuyla: robots.txt kurallari, yonlendirme, bekleme, sikistirma. */
class PoliteHttpClientTest {

    private static final String UA = "KesintiHaritasi/test (+https://example.org)";

    private HttpServer server;
    private String base;
    private final AtomicInteger robotsHits = new AtomicInteger();
    private final AtomicInteger privateHits = new AtomicInteger();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private volatile int robotsStatus = 200;
    private volatile String robotsBody = "User-agent: *\nDisallow: /private/\n";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", ex -> {
            robotsHits.incrementAndGet();
            respond(ex, robotsStatus, robotsBody.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/ok", ex -> {
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            respond(ex, 200, gzip("merhaba dünya"));
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/private/x");
            respond(ex, 302, new byte[0]);
        });
        server.createContext("/private", ex -> {
            privateHits.incrementAndGet();
            respond(ex, 200, "gizli".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static PoliteHttpClient client(Duration minDelay) {
        return new PoliteHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                UA, minDelay, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofMinutes(1), Clock.systemUTC());
    }

    /** robotsTtl'i disaridan veren hali: onbellek davranisini denemek icin. */
    private static PoliteHttpClient client(Duration minDelay, Duration robotsTtl) {
        return new PoliteHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                UA, minDelay, Duration.ofSeconds(5), robotsTtl, Duration.ofMinutes(1), Clock.systemUTC());
    }

    @Test
    void izinliSayfaIndirilirUserAgentGiderSikistirmaAcilir() throws Exception {
        HttpResult r = client(Duration.ZERO).get(base + "/ok");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).isEqualTo("merhaba dünya");
        assertThat(userAgents).containsExactly(UA);
    }

    @Test
    void robotsYasakladigiYolaIstekGitmez() {
        assertThatThrownBy(() -> client(Duration.ZERO).get(base + "/private/x"))
                .isInstanceOf(RobotsDisallowedException.class);
        assertThat(privateHits).hasValue(0);
    }

    @Test
    void yasakYolaYonlendirmeIzlenmez() {
        assertThatThrownBy(() -> client(Duration.ZERO).get(base + "/redirect"))
                .isInstanceOf(RobotsDisallowedException.class);
        assertThat(privateHits).hasValue(0);
    }

    @Test
    void robotsSunucuHatasiVerirseHicIstekGitmez() {
        robotsStatus = 503;
        assertThatThrownBy(() -> client(Duration.ZERO).get(base + "/ok"))
                .isInstanceOf(RobotsDisallowedException.class);
        assertThat(userAgents).isEmpty();
    }

    @Test
    void robotsYoksaKisitYok() throws Exception {
        robotsStatus = 404;
        assertThat(client(Duration.ZERO).get(base + "/private/x").status()).isEqualTo(200);
        assertThat(privateHits).hasValue(1);
    }

    @Test
    void robotsBirKezIndirilir() throws Exception {
        PoliteHttpClient c = client(Duration.ZERO);
        c.get(base + "/ok");
        c.get(base + "/ok");
        assertThat(robotsHits).hasValue(1);
    }

    @Test
    void ayniHostaIsteklerArasindaBeklenir() throws Exception {
        PoliteHttpClient c = client(Duration.ofMillis(300));
        long t0 = System.nanoTime();
        c.get(base + "/ok");
        c.get(base + "/ok");
        // robots.txt + /ok + /ok: aralarinda en az 2 x 300 ms
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(550));
    }

    @Test
    void crawlDelayUygulanir() throws Exception {
        robotsBody = "User-agent: *\nCrawl-delay: 0.5\n";
        PoliteHttpClient c = client(Duration.ZERO);
        long t0 = System.nanoTime();
        c.get(base + "/ok");
        c.get(base + "/ok");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }

    @Test
    void robotsAlinamazsaHataMesajiSebebiSoyler() {
        robotsStatus = 503;
        assertThatThrownBy(() -> client(Duration.ZERO).get(base + "/ok"))
                .isInstanceOf(RobotsDisallowedException.class)
                .hasMessageContaining("alinamadi")
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void robotsAlinamazsaOncekiKopyaKullanilir() throws Exception {
        // robotsTtl sifir: her istekte yeniden indirilmeye calisiliyor
        PoliteHttpClient c = client(Duration.ZERO, Duration.ZERO);
        assertThat(c.get(base + "/ok").status()).isEqualTo(200);

        robotsStatus = 503;
        assertThat(c.get(base + "/ok").status()).as("elde gecerli kopya varken istek durmuyor").isEqualTo(200);
        assertThatThrownBy(() -> c.get(base + "/private/x"))
                .as("kopyadaki yasak kurali gecerli")
                .isInstanceOf(RobotsDisallowedException.class)
                .hasMessageContaining("izin vermiyor");
        assertThat(privateHits).hasValue(0);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
