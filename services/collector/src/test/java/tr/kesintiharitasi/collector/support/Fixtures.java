package tr.kesintiharitasi.collector.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** src/test/resources/fixtures altindaki kayitli kaynak yanitlari. Testler canli siteye gitmez. */
public final class Fixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    public static byte[] bytes(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture yok: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String path) {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    public static JsonNode json(String path) {
        return JSON.readTree(bytes(path));
    }
}
