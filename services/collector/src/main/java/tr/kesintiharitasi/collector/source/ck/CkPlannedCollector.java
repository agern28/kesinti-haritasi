package tr.kesintiharitasi.collector.source.ck;

import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/** Planli kesintiler: tek istek, sirketin tum bolgesi. */
public class CkPlannedCollector implements SourceCollector {

    private final CkCompany company;
    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final CkPlannedParser parser = new CkPlannedParser();

    public CkPlannedCollector(CkCompany company, PoliteHttpClient http, JsonMapper json) {
        this.company = company;
        this.http = http;
        this.json = json;
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
        JsonNode root = json.readTree(http.get(company.plannedUrl()).requireOk().body());
        if (!root.isArray()) {
            throw new IllegalStateException(company.code() + " GetItemsData dizi donmedi");
        }
        return CollectResult.of(parser.parse(root, company));
    }
}
