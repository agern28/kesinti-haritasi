package tr.kesintiharitasi.collector.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import tr.kesintiharitasi.collector.config.CollectorProperties;
import tr.kesintiharitasi.collector.source.SourceCollector;

/**
 * Her feed icin ayri zamanlama. Aralik config'ten (collector.feeds.&lt;kaynak&gt;-&lt;feed&gt;.interval),
 * yoksa collector'in varsayilani. Fixed delay: bir tarama bitmeden ayni feed'in sonraki taramasi baslamaz.
 * Feed'ler ayri gorevler oldugu icin biri takilsa ya da hata verse digerleri calismaya devam eder.
 */
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final List<SourceCollector> collectors;
    private final ScanRunner runner;
    private final TaskScheduler scheduler;
    private final CollectorProperties props;
    private final Clock clock;
    private final SourceStatusStore status;

    public ScanScheduler(List<SourceCollector> collectors, ScanRunner runner, CollectorMetrics metrics,
                         TaskScheduler scheduler, CollectorProperties props, Clock clock, SourceStatusStore status) {
        this.collectors = collectors.stream().filter(c -> props.feed(c.id()).enabled()).toList();
        this.runner = runner;
        this.scheduler = scheduler;
        this.props = props;
        this.clock = clock;
        this.status = status;
        this.collectors.forEach(c -> metrics.register(c.id()));
    }

    public List<SourceCollector> enabledCollectors() {
        return collectors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.scheduling().enabled()) {
            log.info("Zamanlama kapali (collector.scheduling.enabled=false), tarama yapilmayacak");
            return;
        }
        long spread = props.scheduling().initialDelayMax().toMillis();
        for (SourceCollector c : collectors) {
            Duration interval = interval(c);
            try {
                status.registered(c.id(), interval);
            } catch (RuntimeException e) {
                log.warn("{} durumu kaydedilemedi: {}", c.id(), e.toString());
            }
            Duration firstDelay = Duration.ofMillis(spread > 0 ? ThreadLocalRandom.current().nextLong(spread + 1) : 0);
            scheduler.scheduleWithFixedDelay(() -> runner.run(c), clock.instant().plus(firstDelay), interval);
            log.info("{} her {} bir taranacak (ilk tarama {} sn sonra)", c.id(), interval, firstDelay.toSeconds());
        }
    }

    Duration interval(SourceCollector c) {
        Duration configured = props.feed(c.id()).interval();
        Duration interval = configured != null ? configured : c.defaultInterval();
        // Kural: en sik 5 dakika.
        return interval.compareTo(Duration.ofMinutes(5)) < 0 ? Duration.ofMinutes(5) : interval;
    }
}
