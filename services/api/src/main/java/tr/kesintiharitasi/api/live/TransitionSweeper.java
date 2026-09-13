package tr.kesintiharitasi.api.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tr.kesintiharitasi.api.config.ApiProperties;
import tr.kesintiharitasi.api.outage.Outage;
import tr.kesintiharitasi.api.outage.OutageRepository;
import tr.kesintiharitasi.api.summary.SummaryCache;

/**
 * Kaynaktan olay gelmeden de kesinti durumu degisir: planli kesinti saati gelince baslar, bitis saati gelince
 * biter. Bu gorev son calismadan bu yana baslayanlar icin outage.updated, bitenler icin outage.ended yayinlar
 * ve ilgili ilcelerin ozetini gunceller. Birden fazla pod varken ayni anda tek pod calistirir (Redis kilidi).
 */
public class TransitionSweeper {

    private static final Logger log = LoggerFactory.getLogger(TransitionSweeper.class);
    static final String LOCK = "api:sweeper:lock";
    static final String LAST = "api:sweeper:last";

    private final OutageRepository repository;
    private final SummaryCache summary;
    private final LiveEvents live;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ApiProperties.Sweeper props;
    private final String instanceId = UUID.randomUUID().toString();

    public TransitionSweeper(OutageRepository repository, SummaryCache summary, LiveEvents live,
                             StringRedisTemplate redis, Clock clock, ApiProperties.Sweeper props) {
        this.repository = repository;
        this.summary = summary;
        this.live = live;
        this.redis = redis;
        this.clock = clock;
        this.props = props;
    }

    /** @return yayinlanan olay sayisi; kilit baskasindaysa -1 */
    public int sweep() {
        Duration lockTtl = props.interval().minusSeconds(1).isNegative() || props.interval().minusSeconds(1).isZero()
                ? Duration.ofSeconds(1) : props.interval().minusSeconds(1);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK, instanceId, lockTtl))) {
            return -1;
        }
        try {
            Instant now = clock.instant();
            String lastRaw = redis.opsForValue().get(LAST);
            Instant from = lastRaw == null ? now.minus(props.interval()) : Instant.parse(lastRaw);
            if (from.isBefore(now.minus(props.maxWindow()))) {
                from = now.minus(props.maxWindow());
            }
            int published = 0;
            for (Outage o : repository.transitions(from, now)) {
                boolean ended = o.endsAt() != null && o.endsAt().isAfter(from) && !o.endsAt().isAfter(now);
                summary.refresh(o.ilKey(), o.ilceKey());
                live.publish(ended ? LiveEvents.ENDED : LiveEvents.UPDATED, o);
                published++;
            }
            redis.opsForValue().set(LAST, now.toString());
            if (published > 0) {
                log.info("zamanla baslayan/biten {} kesinti yayinlandi", published);
            }
            return published;
        } catch (RuntimeException e) {
            log.warn("zaman taramasi basarisiz: {}", e.toString());
            return 0;
        }
    }
}
