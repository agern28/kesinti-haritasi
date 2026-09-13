package tr.kesintiharitasi.collector.source.ck;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.collector.http.HttpResult;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.pipeline.StateStore;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;
import tr.kesintiharitasi.collector.source.ck.CkUnplannedParser.CkFault;
import tr.kesintiharitasi.collector.source.ck.CkUnplannedParser.TmLocation;

/**
 * Anlik kesintiler (arizalar). Trafo -> konum eslemesi Redis'te 30 gun tutulur; sadece ilk kez gorulen
 * trafolar icin GetLocation cagrilir ve tarama basina bu cagri sayisi sinirlidir. BEDAŞ'in kendi sitesi her
 * 5 dakikada tum trafolari soruyor; biz bunu yapmiyoruz.
 */
public class CkUnplannedCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(CkUnplannedCollector.class);
    private static final Duration LOCATION_TTL = Duration.ofDays(30);
    private static final Duration MISSING_LOCATION_TTL = Duration.ofDays(1);
    private static final String NOT_FOUND = "-";

    private final CkCompany company;
    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final StateStore state;
    private final int lookupsPerScan;

    public CkUnplannedCollector(CkCompany company, PoliteHttpClient http, JsonMapper json, StateStore state,
                                int lookupsPerScan) {
        this.company = company;
        this.http = http;
        this.json = json;
        this.state = state;
        this.lookupsPerScan = lookupsPerScan;
    }

    @Override
    public FeedId id() {
        return new FeedId(company.code(), "unplanned");
    }

    @Override
    public Duration defaultInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public CollectResult collect() throws Exception {
        JsonNode root = json.readTree(http.get(company.unplannedUrl()).requireOk().body());
        if (!root.has("Outage")) {
            throw new IllegalStateException(company.code() + " RetrieveOutages beklenen bicimde degil");
        }
        List<CkFault> faults = CkUnplannedParser.faults(root);
        Map<String, TmLocation> locations = new HashMap<>();
        int budget = lookupsPerScan;
        for (CkFault f : faults) {
            for (String tm : f.transformers()) {
                if (locations.containsKey(tm)) {
                    continue;
                }
                String cached = state.get(cacheKey(tm));
                if (cached != null) {
                    decode(cached).ifPresent(loc -> locations.put(tm, loc));
                    continue;
                }
                if (budget <= 0) {
                    continue;
                }
                budget--;
                lookup(tm).ifPresent(loc -> locations.put(tm, loc));
            }
        }
        List<Outage> outages = CkUnplannedParser.toOutages(company, faults, locations);
        long located = outages.stream().map(o -> o.externalId().split("/", 2)[0]).distinct().count();
        if (located < faults.size()) {
            log.info("{}: {} arizadan {} tanesinin konumu henuz yok, sonraki taramalarda gelecek",
                    id(), faults.size(), faults.size() - located);
        }
        return CollectResult.of(outages);
    }

    private Optional<TmLocation> lookup(String tm) throws InterruptedException {
        try {
            HttpResult r = http.get(company.locationUrl(tm));
            if (!r.ok()) {
                return Optional.empty();
            }
            Optional<TmLocation> loc = CkUnplannedParser.location(json.readTree(r.body()));
            state.set(cacheKey(tm), loc.map(l -> l.ilce() + "\t" + (l.mahalle() == null ? "" : l.mahalle()))
                    .orElse(NOT_FOUND), loc.isPresent() ? LOCATION_TTL : MISSING_LOCATION_TTL);
            return loc;
        } catch (java.io.IOException | RuntimeException e) {
            // Tek trafonun konumu alinamazsa tarama bozulmaz; o trafo sonraki taramada tekrar denenir.
            log.debug("{} trafo {} konumu alinamadi: {}", id(), tm, e.toString());
            return Optional.empty();
        }
    }

    private String cacheKey(String tm) {
        return "collector:tm:" + company.code() + ":" + tm;
    }

    private static Optional<TmLocation> decode(String cached) {
        if (NOT_FOUND.equals(cached)) {
            return Optional.empty();
        }
        String[] parts = cached.split("\t", 2);
        return Optional.of(new TmLocation(parts[0], parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null));
    }
}
