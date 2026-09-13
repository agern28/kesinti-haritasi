package tr.kesintiharitasi.collector.source.iski;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tr.kesintiharitasi.collector.http.PoliteHttpClient;
import tr.kesintiharitasi.collector.model.FeedId;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.pipeline.SnapshotStore;
import tr.kesintiharitasi.collector.pipeline.StateStore;
import tr.kesintiharitasi.collector.source.CollectResult;
import tr.kesintiharitasi.collector.source.SourceCollector;

/**
 * İSKİ su kesintileri, İBB Açık Veri'den (İSKİ'nin kendi API'si gomulu token istiyor, kullanmiyoruz).
 * Gunde bir veri seti sayfasi okunur; dosya linkleri degismediyse dosyalar indirilmez ve diff yapilmaz.
 * data.ibb.gov.tr robots.txt'i /api/'yi yasakliyor ve Crawl-delay: 10 diyor; CKAN API'sine gitmiyoruz,
 * dosya listesini sayfanin HTML'inden aliyoruz. Bekleme PoliteHttpClient'ta.
 */
public class IbbIskiCollector implements SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(IbbIskiCollector.class);
    public static final String DATASET_URL = "https://data.ibb.gov.tr/dataset/istanbul-da-meydana-gelen-su-kesintileri";
    private static final String FILES_KEY = "collector:ibb:iski:files";

    private final PoliteHttpClient http;
    private final SnapshotStore snapshots;
    private final StateStore state;
    private final IbbWaterOutageParser parser = new IbbWaterOutageParser();

    public IbbIskiCollector(PoliteHttpClient http, SnapshotStore snapshots, StateStore state) {
        this.http = http;
        this.snapshots = snapshots;
        this.state = state;
    }

    @Override
    public FeedId id() {
        return new FeedId("ISKI", "daily");
    }

    @Override
    public Duration defaultInterval() {
        return Duration.ofHours(24);
    }

    @Override
    public CollectResult collect() throws Exception {
        List<String> links = xlsxLinks(http.get(DATASET_URL).requireOk().text());
        if (links.isEmpty()) {
            throw new IllegalStateException("İBB veri seti sayfasinda xlsx linki bulunamadi");
        }
        String marker = String.join("\n", links);
        if (marker.equals(state.get(FILES_KEY)) && snapshots.exists(id())) {
            return CollectResult.unchangedSinceLastScan();
        }
        List<Outage> all = new ArrayList<>();
        for (String link : links) {
            byte[] file = http.get(link).requireOk().body();
            IbbWaterOutageParser.Result r = parser.parse(XlsxReader.readFirstSheet(file), DATASET_URL);
            if (r.skippedRows() > 0) {
                log.info("{}: {} dosyasinda {} satir okunamadi", id(), link, r.skippedRows());
            }
            all.addAll(r.outages());
        }
        return CollectResult.of(all).onCommit(() -> state.set(FILES_KEY, marker, null));
    }

    /** Veri seti sayfasindaki xlsx indirme linkleri, sirali ve tekrarsiz. */
    public static List<String> xlsxLinks(String html) {
        Set<String> links = new LinkedHashSet<>();
        for (Element a : Jsoup.parse(html, DATASET_URL).select("a[href]")) {
            String href = a.absUrl("href");
            if (href.contains("/download/") && href.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
                links.add(href);
            }
        }
        return links.stream().sorted().toList();
    }
}
