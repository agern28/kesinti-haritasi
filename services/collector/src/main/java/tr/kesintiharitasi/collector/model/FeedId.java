package tr.kesintiharitasi.collector.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Bir kaynagin taranan tek bir sayfasi/endpoint'i. Ornek: BEDAS/planned, BEDAS/unplanned.
 * Zamanlama, snapshot ve metrikler bu kimlik uzerinden tutulur.
 */
public record FeedId(String source, String feed) {

    public FeedId {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(feed, "feed");
    }

    /** Config anahtari: collector.feeds.bedas-planned gibi. */
    public String key() {
        return source.toLowerCase(Locale.ROOT) + "-" + feed;
    }

    @Override
    public String toString() {
        return source + "/" + feed;
    }
}
