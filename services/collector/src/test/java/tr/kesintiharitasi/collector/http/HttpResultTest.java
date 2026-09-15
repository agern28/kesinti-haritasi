package tr.kesintiharitasi.collector.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpResultTest {

    private static final URI URL = URI.create("https://example.org/");

    private static HttpResult result(String body) {
        return new HttpResult(200, URL, "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ayniGovdeIceriginiTasiyanlarEsit() {
        assertThat(result("a")).isEqualTo(result("a")).hasSameHashCodeAs(result("a"));
        assertThat(result("a")).isNotEqualTo(result("b"));
        assertThat(result("a")).isNotEqualTo(new HttpResult(404, URL, "text/html; charset=UTF-8", "a".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void toStringGovdeyiDegilBoyutunuYaziyor() {
        assertThat(result("gizli sayfa").toString()).contains("11 bayt").doesNotContain("gizli");
    }

    @Test
    void karakterSetiBasliktan() {
        Charset tr = Charset.forName("windows-1254");
        HttpResult r = new HttpResult(200, URL, "text/html; charset=\"windows-1254\"", "Şişli".getBytes(tr));
        assertThat(r.text()).isEqualTo("Şişli");
        assertThat(new HttpResult(200, URL, null, "Şişli".getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("Şişli");
    }
}
