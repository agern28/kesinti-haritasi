package tr.kesintiharitasi.collector.pipeline;

import java.util.Map;
import tr.kesintiharitasi.collector.model.FeedId;

/** Bir feed'in son basarili taramasindaki kayitlar: dedup_key -> content_hash. */
public interface SnapshotStore {

    Map<String, String> load(FeedId feed);

    void save(FeedId feed, Map<String, String> snapshot);

    boolean exists(FeedId feed);
}
