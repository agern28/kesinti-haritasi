package tr.kesintiharitasi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tr.kesintiharitasi.api.support.IntegrationTest;

class HealthEndpointTest extends IntegrationTest {

    @Test
    void healthIsUp() throws Exception {
        var res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessAreSeparate() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        var readiness = get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
    }

    @Test
    void prometheusShowsApiMetrics() throws Exception {
        var res = get("/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("jvm_memory_used_bytes")
                .contains("api_sse_clients")
                .contains("api_summary_cache_total");
    }

    @Test
    void swaggerUiVeOpenApi() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/api/outages", "/api/outages/{id}", "/api/map/summary", "/api/sources",
                "/api/stream");
        HttpClient follow = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        var ui = follow.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }

    @Test
    void canliOlaySayfasi() throws Exception {
        var page = get("/canli.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("new EventSource('/api/stream')");
    }
}
