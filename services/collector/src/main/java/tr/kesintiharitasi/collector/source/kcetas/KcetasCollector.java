package tr.kesintiharitasi.collector.source.kcetas;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/** KCETAŞ: tarih basina tek POST. Bugun ve sonraki daysAhead gun sorgulanir. */
public class KcetasCollector implements SourceCollector {

    private static final String QUERY_URL = "https://www.kcetas.com.tr/kesinti-sorgu.php";

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final int daysAhead;
    private final KcetasParser parser = new KcetasParser();

    public KcetasCollector(PoliteHttpClient http, JsonMapper json, Clock clock, int daysAhead) {
        this.http = http;
        this.json = json;
        this.clock = clock;
        this.daysAhead = daysAhead;
    }

    @Override
    public FeedId id() {
        return new FeedId("KCETAS", "planned");
    }

    @Override
    public Duration defaultInterval() {
        return Duration.ofMinutes(15);
    }

    @Override
    public CollectResult collect() throws Exception {
        LocalDate today = LocalDate.now(clock.withZone(Dates.ISTANBUL));
        List<Outage> all = new ArrayList<>();
        for (int i = 0; i <= daysAhead; i++) {
            String body = "bakim_tarih=" + today.plusDays(i);
            all.addAll(parser.parse(json.readTree(http.postForm(QUERY_URL, body).requireOk().body())));
        }
        return CollectResult.of(all);
    }
}
