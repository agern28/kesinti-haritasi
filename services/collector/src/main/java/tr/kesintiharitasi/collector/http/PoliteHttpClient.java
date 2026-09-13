package tr.kesintiharitasi.collector.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kaynak sitelere giden tum istekler buradan gecer.
 * <ul>
 *   <li>Her istekten once host'un robots.txt'i kontrol edilir; izin yoksa istek gonderilmez.</li>
 *   <li>Yonlendirmeler elle izlenir, her adimda hedefin robots.txt'i yeniden kontrol edilir.</li>
 *   <li>Ayni host'a istekler sirayla gider, aralarinda en az max(minHostDelay, Crawl-delay) beklenir.</li>
 *   <li>Anlamli User-Agent gonderilir.</li>
 * </ul>
 */
public class PoliteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);
    private static final int MAX_REDIRECTS = 5;

    private record CachedRobots(RobotsTxt rules, Instant expiresAt) {
    }

    private static final class HostGate {
        final ReentrantLock lock = new ReentrantLock(true);
        Instant lastRequestAt = Instant.EPOCH;
    }

    private final HttpClient http;
    private final String userAgent;
    private final String productToken;
    private final Duration minHostDelay;
    private final Duration requestTimeout;
    private final Duration robotsTtl;
    private final Duration robotsFailureTtl;
    private final Clock clock;
    private final Map<String, HostGate> gates = new ConcurrentHashMap<>();
    private final Map<String, CachedRobots> robots = new ConcurrentHashMap<>();

    public PoliteHttpClient(HttpClient http, String userAgent, Duration minHostDelay, Duration requestTimeout,
                            Duration robotsTtl, Duration robotsFailureTtl, Clock clock) {
        this.http = http;
        this.userAgent = userAgent;
        this.productToken = userAgent.split("[/\\s]", 2)[0];
        this.minHostDelay = minHostDelay;
        this.requestTimeout = requestTimeout;
        this.robotsTtl = robotsTtl;
        this.robotsFailureTtl = robotsFailureTtl;
        this.clock = clock;
    }

    public HttpResult get(String url) throws IOException, InterruptedException {
        return send("GET", URI.create(url), null, null);
    }

    public HttpResult postForm(String url, String formBody) throws IOException, InterruptedException {
        return send("POST", URI.create(url), formBody.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded; charset=UTF-8");
    }

    private HttpResult send(String method, URI uri, byte[] body, String contentType)
            throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            RobotsTxt rules = robotsFor(current);
            if (!rules.allows(pathAndQuery(current))) {
                throw new RobotsDisallowedException(current);
            }
            HttpResponse<byte[]> resp = gated(current, rules.crawlDelay(), request(method, current, body, contentType));
            int status = resp.statusCode();
            String location = resp.headers().firstValue("location").orElse(null);
            if (isRedirect(status) && location != null) {
                current = current.resolve(location.strip());
                if (status != 307 && status != 308) {
                    method = "GET";
                    body = null;
                    contentType = null;
                }
                continue;
            }
            return new HttpResult(status, current, resp.headers().firstValue("content-type").orElse(null), decode(resp));
        }
        throw new IOException("Cok fazla yonlendirme: " + uri);
    }

    /** Host'un robots.txt kurallari; gerekirse indirir ve cache'ler. */
    RobotsTxt robotsFor(URI uri) throws InterruptedException {
        String origin = origin(uri);
        Instant now = clock.instant();
        CachedRobots cached = robots.get(origin);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.rules();
        }
        RobotsTxt rules;
        Duration ttl = robotsTtl;
        try {
            URI current = URI.create(origin + "/robots.txt");
            HttpResponse<byte[]> resp = null;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                resp = gated(current, null, request("GET", current, null, null));
                String location = resp.headers().firstValue("location").orElse(null);
                if (isRedirect(resp.statusCode()) && location != null) {
                    current = current.resolve(location.strip());
                    continue;
                }
                break;
            }
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                rules = RobotsTxt.parse(new String(decode(resp), StandardCharsets.UTF_8), productToken);
            } else if ((status >= 400 && status < 500) || isRedirect(status)) {
                rules = RobotsTxt.allowAll();
            } else {
                rules = RobotsTxt.disallowAll();
                ttl = robotsFailureTtl;
            }
            log.info("robots.txt {} -> HTTP {}", origin, status);
        } catch (IOException e) {
            log.warn("robots.txt {} alinamadi, tamamen yasak sayiliyor: {}", origin, e.toString());
            rules = RobotsTxt.disallowAll();
            ttl = robotsFailureTtl;
        }
        robots.put(origin, new CachedRobots(rules, now.plus(ttl)));
        return rules;
    }

    private HttpResponse<byte[]> gated(URI uri, Duration crawlDelay, HttpRequest request)
            throws IOException, InterruptedException {
        HostGate gate = gates.computeIfAbsent(uri.getHost().toLowerCase(Locale.ROOT), h -> new HostGate());
        Duration delay = crawlDelay != null && crawlDelay.compareTo(minHostDelay) > 0 ? crawlDelay : minHostDelay;
        gate.lock.lockInterruptibly();
        try {
            long waitMs = Duration.between(clock.instant(), gate.lastRequestAt.plus(delay)).toMillis();
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } finally {
                gate.lastRequestAt = clock.instant();
            }
        } finally {
            gate.lock.unlock();
        }
    }

    private HttpRequest request(String method, URI uri, byte[] body, String contentType) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "gzip, deflate");
        if (body != null) {
            b.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return b.build();
    }

    /** Bazi sunucular istemesek de sikistirilmis gonderiyor; basliga ve sihirli baytlara bakilir. */
    static byte[] decode(HttpResponse<byte[]> resp) throws IOException {
        byte[] raw = resp.body();
        String encoding = resp.headers().firstValue("content-encoding").orElse("").toLowerCase(Locale.ROOT);
        boolean gzip = encoding.contains("gzip") || (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b);
        if (gzip) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return in.readAllBytes();
            }
        }
        if (encoding.contains("deflate")) {
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(raw))) {
                return in.readAllBytes();
            }
        }
        return raw;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }
}
