package tr.kesintiharitasi.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Abonelik kurulmadan pod hazir sayilmamali: o aralikta yayinlanan olaylar kayboluyor. */
class SseFanoutHealthIndicatorTest {

    private SseFanoutHealthIndicator indicator(boolean dinliyor) {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        when(container.isListening()).thenReturn(dinliyor);
        return new SseFanoutHealthIndicator(container, "outage-updates");
    }

    @Test
    @DisplayName("abone olunduysa UP ve kanal adi detayda")
    void aboneyseUp() {
        var health = indicator(true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("kanal", "outage-updates").containsEntry("abone", true);
    }

    @Test
    @DisplayName("abone olunmadiysa DOWN")
    void aboneDegilseDown() {
        var health = indicator(false).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("abone", false);
    }
}
