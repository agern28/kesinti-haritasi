package tr.kesintiharitasi.collector.source.izsu;

import java.time.Duration;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/**
 * İZSU: planli ve ariza tek sayfada, bu yuzden tek feed ve 5 dakika.
 * robots.txt /api/'yi yasakliyor; biz API'ye degil izinli sayfaya gidiyoruz.
 */
public class IzsuCollector implements SourceCollector {

    private final PoliteHttpClient http;
    private final IzsuParser parser = new IzsuParser();

    public IzsuCollector(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public FeedId id() {
        return new FeedId("IZSU", "all");
    }

    @Override
    public Duration defaultInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public CollectResult collect() throws Exception {
        return CollectResult.of(parser.parse(http.get(IzsuParser.SOURCE_URL).requireOk().text()));
    }
}
