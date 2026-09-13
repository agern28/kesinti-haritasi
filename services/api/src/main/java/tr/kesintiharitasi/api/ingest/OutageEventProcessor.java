package tr.kesintiharitasi.api.ingest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tr.kesintiharitasi.api.live.LiveEvents;
import tr.kesintiharitasi.api.outage.IncomingOutage;
import tr.kesintiharitasi.api.outage.Outage;
import tr.kesintiharitasi.api.outage.OutageRepository;
import tr.kesintiharitasi.api.summary.SummaryCache;

/**
 * Collector'dan gelen tek bir olay: veritabanina yaz, degistiyse ilce ozetini guncelle ve tarayicilara yayinla.
 * NEW/UPDATED -> upsert (satir yeni eklendiyse outage.created, degistiyse outage.updated)
 * GONE        -> gone_at doldurulur (outage.ended)
 * Ayni olay ikinci kez gelirse veritabani degismez ve yayin olmaz.
 */
public class OutageEventProcessor {

    public enum Result {
        APPLIED,
        NOOP
    }

    private final OutageRepository repository;
    private final SummaryCache summary;
    private final LiveEvents live;
    private final JsonMapper json;
    private final java.time.Clock clock;

    public OutageEventProcessor(OutageRepository repository, SummaryCache summary, LiveEvents live, JsonMapper json,
                                java.time.Clock clock) {
        this.repository = repository;
        this.summary = summary;
        this.live = live;
        this.json = json;
        this.clock = clock;
    }

    public Result process(Map<String, String> fields) {
        String event = required(fields, "event");
        String dedupKey = required(fields, "dedupKey");
        Instant scannedAt = Instant.parse(required(fields, "scannedAt"));
        switch (event) {
            case "NEW", "UPDATED" -> {
                IncomingOutage in = json.readValue(required(fields, "payload"), IncomingOutage.class);
                if (in.source() == null || in.il() == null || in.ilce() == null || in.startsAt() == null
                        || in.type() == null || in.sourceUrl() == null) {
                    throw new BadEventException("payload eksik: " + dedupKey);
                }
                Optional<OutageRepository.Upserted> r = repository.upsert(dedupKey, required(fields, "contentHash"), in, scannedAt);
                if (r.isEmpty()) {
                    return Result.NOOP;
                }
                Outage o = r.get().outage();
                if (r.get().created() && o.endsAt() != null && !o.endsAt().isAfter(clock.instant())) {
                    // Kaynaktan ilk geldiginde zaten bitmis kesinti (ornegin İBB'nin 2022-2024 verisi): veritabaninda
                    // kalir ama tarayicilara yayinlanmaz, haritada zaten aktif gorunmez.
                    return Result.APPLIED;
                }
                announce(r.get().created() ? LiveEvents.CREATED : LiveEvents.UPDATED, o);
                return Result.APPLIED;
            }
            case "GONE" -> {
                Optional<Outage> gone = repository.markGone(dedupKey, scannedAt);
                if (gone.isEmpty()) {
                    return Result.NOOP;
                }
                announce(LiveEvents.ENDED, gone.get());
                return Result.APPLIED;
            }
            default -> throw new BadEventException("bilinmeyen olay turu: " + event);
        }
    }

    private void announce(String type, Outage outage) {
        summary.refresh(outage.ilKey(), outage.ilceKey());
        live.publish(type, outage);
    }

    private static String required(Map<String, String> fields, String name) {
        String v = fields.get(name);
        if (v == null || v.isBlank()) {
            throw new BadEventException("alan eksik: " + name);
        }
        return v;
    }
}
