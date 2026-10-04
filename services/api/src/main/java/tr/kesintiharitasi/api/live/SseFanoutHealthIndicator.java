package tr.kesintiharitasi.api.live;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Pod canli olay dagitimina hazir mi: Redis Pub/Sub kanalina abone oldu mu.
 *
 * <p>Neden readiness'a bagli: olaylar Pub/Sub ile dagitiliyor ve Pub/Sub'in gecmisi yok. Spring'in
 * dinleyici container'i abonelige acilista asenkron gidiyor; abone olmadan once yayinlanan bir olay
 * bu pod'a hic ulasmiyor. Pod o aralikta trafik alirsa bagli tarayicilar o olayi kacirir ve harita
 * sessizce eski kalir -- hata da gorunmez, cunku istek basarili sayilir.
 *
 * <p>Bu yarisi CI'da kirilan bir test yakalamisti (2026-09-23, TransitionSweeperTest: "beklenen olay
 * gelmedi"). Artik abonelik kurulana kadar pod hazir sayilmiyor, Kubernetes de Service'e eklemiyor.
 */
public class SseFanoutHealthIndicator implements HealthIndicator {

    private final RedisMessageListenerContainer listener;
    private final String kanal;

    public SseFanoutHealthIndicator(RedisMessageListenerContainer listener, String kanal) {
        this.listener = listener;
        this.kanal = kanal;
    }

    @Override
    public Health health() {
        boolean abone = listener.isListening();
        return (abone ? Health.up() : Health.down())
                .withDetail("kanal", kanal)
                .withDetail("abone", abone)
                .build();
    }
}
