package tr.kesintiharitasi.collector.http;

import java.io.IOException;
import java.net.URI;

/** robots.txt izin vermedigi icin istek hic gonderilmedi. */
public class RobotsDisallowedException extends IOException {

    public RobotsDisallowedException(URI uri) {
        super("robots.txt izin vermiyor: " + uri);
    }
}
