package tr.kesintiharitasi.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tr.kesintiharitasi.api.ApiApplication;
import tr.kesintiharitasi.api.support.Containers;
import tr.kesintiharitasi.api.support.IntegrationTest;
import tr.kesintiharitasi.api.support.SseTestClient;
import tr.kesintiharitasi.api.support.SseTestClient.Event;

/**
 * Iki api ornegi (a: test context'i, b: ayni JVM'de ikinci uygulama), ayni PostgreSQL ve Redis.
 * Olay stream'den tek bir ornek tarafindan islenir ama Redis Pub/Sub sayesinde iki ornege bagli istemciler de alir.
 */
class MultiInstanceSseTest extends IntegrationTest {

    private static ConfigurableApplicationContext second;
    private static int secondPort;

    @BeforeAll
    static void startSecondInstance() {
        // properties() varsayilan (en dusuk oncelikli) ozellikleri ayarlar; application.yml onlari ezer.
        // Komut satiri argumanlari ise application.yml'den once gelir.
        String[] args = java.util.stream.Stream.concat(java.util.Arrays.stream(Containers.properties()),
                        java.util.stream.Stream.of("server.port=0", "api.stream.consumer=b"))
                .map(p -> "--" + p).toArray(String[]::new);
        second = new SpringApplicationBuilder(ApiApplication.class).profiles("test").run(args);
        secondPort = second.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterAll
    static void stopSecondInstance() {
        if (second != null) {
            second.close();
        }
    }

    @Test
    void olayIkiOrnekteDeIstemcilereUlasir() throws Exception {
        String ilce = unique("KARSIYAKA");
        String key = unique("TEST:");
        try (SseTestClient onA = SseTestClient.connect(port, null).awaitConnected();
             SseTestClient onB = SseTestClient.connect(secondPort, null).awaitConnected()) {
            Instant now = Instant.now();
            publish("NEW", key, "h1", payload("İZMİR", ilce, List.of("BOSTANLI"), "WATER", false,
                    now.minusSeconds(60), now.plusSeconds(600)), now);

            Event a = onA.next(e -> e.data().contains(ilce), Duration.ofSeconds(10));
            Event b = onB.next(e -> e.data().contains(ilce), Duration.ofSeconds(10));
            assertThat(a.name()).isEqualTo(LiveEvents.CREATED);
            assertThat(b.id()).as("ayni olay, ayni kimlik").isEqualTo(a.id());
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> rowCount(key) == 1);
        assertThat(redis.opsForStream().consumers("outage-events", "api"))
                .extracting(c -> c.consumerName()).contains("a", "b");
    }
}
