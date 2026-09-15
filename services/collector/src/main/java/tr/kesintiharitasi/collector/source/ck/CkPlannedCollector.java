package tr.kesintiharitasi.collector.source.ck;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/**
 * Planli kesintiler: tek istek, sirketin tum bolgesi.
 *
 * <p>ÇEDAŞ'in sitesi bir yuk dengeleyicinin (F5 BIG-IP) arkasinda ve sunuculardan biri Haziran 2024'ten kalma
 * listeyi donduruyor (2026-09-15'te goruldu). Boyle bir cevap guncel listeyi tamamen GONE, eski listeyi NEW
 * yapiyordu; bir sonraki taramada tersi. En yeni kesintisi {@link #STALE_AFTER}'dan eski olan liste eski
 * sunucudan gelmis sayiliyor ve bir kez daha isteniyor. Yine eskiyse tarama basarisiz: snapshot korunuyor,
 * hicbir sey GONE olmuyor.
 */
public class CkPlannedCollector implements SourceCollector {

    static final Duration STALE_AFTER = Duration.ofDays(2);
    static final int ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(CkPlannedCollector.class);

    private final CkCompany company;
    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final CkPlannedParser parser = new CkPlannedParser();

    public CkPlannedCollector(CkCompany company, PoliteHttpClient http, JsonMapper json, Clock clock) {
        this.company = company;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public FeedId id() {
        return new FeedId(company.code(), "planned");
    }

    @Override
    public Duration defaultInterval() {
        return Duration.ofMinutes(15);
    }

    @Override
    public CollectResult collect() throws Exception {
        Instant newest = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            JsonNode root = json.readTree(http.get(company.plannedUrl()).requireOk().body());
            if (!root.isArray()) {
                throw new IllegalStateException(company.code() + " GetItemsData dizi donmedi");
            }
            List<Outage> outages = parser.parse(root, company);
            newest = newest(outages);
            // Bos liste eski sayilmiyor: planli kesinti olmayan bir gun de olabilir.
            if (newest == null || !newest.isBefore(clock.instant().minus(STALE_AFTER))) {
                return CollectResult.of(outages);
            }
            log.warn("{} GetItemsData eski veri dondu (en yeni kesinti {}), deneme {}/{}",
                    company.code(), newest, attempt, ATTEMPTS);
        }
        throw new IllegalStateException(company.code() + " GetItemsData eski veri dondu (en yeni kesinti "
                + newest + "), " + ATTEMPTS + " denemede de");
    }

    /** Listedeki en gec bitis (bitisi olmayanda baslangic). Bos listede null. */
    static Instant newest(List<Outage> outages) {
        Instant max = null;
        for (Outage o : outages) {
            Instant t = o.endsAt() != null ? o.endsAt() : o.startsAt();
            if (t != null && (max == null || t.isAfter(max))) {
                max = t;
            }
        }
        return max;
    }
}
