package tr.kesintiharitasi.collector.http;

import java.io.IOException;
import java.net.URI;

/** robots.txt izin vermedigi icin istek hic gonderilmedi. */
public class RobotsDisallowedException extends IOException {

    public RobotsDisallowedException(URI uri) {
        this(uri, null);
    }

    /** unavailable null degilse robots.txt alinamadigi icin yasak sayilmistir; sebebi mesaja giriyor. */
    public RobotsDisallowedException(URI uri, String unavailable) {
        super(unavailable == null ? "robots.txt izin vermiyor: " + uri
                : "robots.txt alinamadi (" + unavailable + "), yasak sayildi: " + uri);
    }
}
