package tr.kesintiharitasi.collector.pipeline;

import java.time.Duration;

/** Collector'in kucuk durum bilgileri (trafo konum cache'i, İBB dosya listesi). */
public interface StateStore {

    String get(String key);

    /** ttl null ise suresiz. */
    void set(String key, String value, Duration ttl);
}
