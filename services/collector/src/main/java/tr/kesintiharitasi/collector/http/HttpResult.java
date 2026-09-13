package tr.kesintiharitasi.collector.http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record HttpResult(int status, URI uri, String contentType, byte[] body) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    /** 2xx degilse IOException; tarama hata olarak sayilir, snapshot degismez. */
    public HttpResult requireOk() throws IOException {
        if (!ok()) {
            throw new IOException("HTTP " + status + " " + uri);
        }
        return this;
    }

    public String text() {
        return new String(body, charset());
    }

    private Charset charset() {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.strip().toLowerCase(Locale.ROOT);
                if (p.startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", ""));
                    } catch (IllegalArgumentException ignored) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
