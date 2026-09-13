package tr.kesintiharitasi.collector.pipeline;

import java.time.Duration;
import java.time.Instant;
import tr.kesintiharitasi.collector.model.FeedId;

/**
 * Feed'lerin son durumu (tarama araligi, son basarili tarama, son hata). api /api/sources icin buradan okur.
 * Redis'te tek bir hash: collector:status, alan "KAYNAK/feed", deger JSON.
 */
public interface SourceStatusStore {

    SourceStatusStore NOOP = new SourceStatusStore() {
        @Override
        public void registered(FeedId feed, Duration interval) {
        }

        @Override
        public void succeeded(FeedId feed, Instant at, Integer items) {
        }

        @Override
        public void failed(FeedId feed, Instant at, String error) {
        }
    };

    void registered(FeedId feed, Duration interval);

    /** items null ise (kaynak degismedi) son kayit sayisi korunur. */
    void succeeded(FeedId feed, Instant at, Integer items);

    void failed(FeedId feed, Instant at, String error);
}
