package tr.kesintiharitasi.api.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Testlerde tarayici yerine: /api/stream'e baglanir, SSE satirlarini olaylara ayirir. */
public final class SseTestClient implements AutoCloseable {

    public record Event(String id, String name, String data) {
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();
    private volatile Stream<String> body;
    private String id;
    private String name;
    private final StringBuilder data = new StringBuilder();

    public static SseTestClient connect(int port, String lastEventId) {
        SseTestClient c = new SseTestClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream");
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        c.client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofLines()).thenAccept(resp -> {
            c.body = resp.body();
            try {
                resp.body().forEach(c::line);
            } catch (RuntimeException ignored) {
                // baglanti kapandi
            }
        });
        return c;
    }

    private synchronized void line(String l) {
        if (l.isEmpty()) {
            if (name != null || data.length() > 0) {
                events.add(new Event(id, name, data.toString()));
            }
            id = null;
            name = null;
            data.setLength(0);
        } else if (l.startsWith(":")) {
            comments.add(l.substring(1).strip());
        } else if (l.startsWith("id:")) {
            id = value(l, 3);
        } else if (l.startsWith("event:")) {
            name = value(l, 6);
        } else if (l.startsWith("data:")) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value(l, 5));
        }
    }

    private static String value(String line, int from) {
        String v = line.substring(from);
        return v.startsWith(" ") ? v.substring(1) : v;
    }

    /** "bagli" yorum satiri gelene kadar bekler (baglanti kuruldu, varsa kacirilanlar gonderildi). */
    public SseTestClient awaitConnected() throws InterruptedException {
        if (!sawComment("bagli", Duration.ofSeconds(10))) {
            throw new AssertionError("SSE baglantisi kurulamadi");
        }
        return this;
    }

    public boolean sawComment(String comment, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String c = comments.poll(50, TimeUnit.MILLISECONDS);
            if (comment.equals(c)) {
                return true;
            }
        }
        return false;
    }

    public Event next(Duration timeout) throws InterruptedException {
        Event e = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (e == null) {
            throw new AssertionError("olay gelmedi");
        }
        return e;
    }

    /**
     * Kosulu saglayan ilk olay. Eslesmeyenler atilmaz, sonraki cagrilar icin saklanir; boylece olaylar
     * beklenenden farkli sirada gelse de kaybolmaz.
     */
    public Event next(Predicate<Event> match, Duration timeout) throws InterruptedException {
        for (java.util.Iterator<Event> it = skipped.iterator(); it.hasNext(); ) {
            Event e = it.next();
            if (match.test(e)) {
                it.remove();
                return e;
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Event e = events.poll(50, TimeUnit.MILLISECONDS);
            if (e == null) {
                continue;
            }
            if (match.test(e)) {
                return e;
            }
            skipped.add(e);
        }
        throw new AssertionError("beklenen olay gelmedi");
    }

    private final java.util.List<Event> skipped = new java.util.ArrayList<>();

    @Override
    public void close() {
        Stream<String> b = body;
        if (b != null) {
            b.close();
        }
        client.shutdownNow();
    }
}
