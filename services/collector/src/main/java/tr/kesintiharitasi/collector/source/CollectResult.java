package tr.kesintiharitasi.collector.source;

import java.util.List;
import tr.kesintiharitasi.collector.model.Outage;

/**
 * Bir taramanin sonucu.
 *
 * @param outages     kaynaktaki guncel kayitlarin tamami
 * @param unchanged   kaynak son taramadan beri degismedi (ör. İBB'de yeni dosya yok); diff yapilmaz
 * @param afterCommit olaylar yazilip snapshot kaydedildikten sonra calisir (ör. "bu dosyalari isledim" isareti)
 */
public record CollectResult(List<Outage> outages, boolean unchanged, Runnable afterCommit) {

    public CollectResult {
        outages = List.copyOf(outages);
        afterCommit = afterCommit == null ? () -> { } : afterCommit;
    }

    public static CollectResult of(List<Outage> outages) {
        return new CollectResult(outages, false, null);
    }

    public static CollectResult unchangedSinceLastScan() {
        return new CollectResult(List.of(), true, null);
    }

    public CollectResult onCommit(Runnable action) {
        return new CollectResult(outages, unchanged, action);
    }
}
