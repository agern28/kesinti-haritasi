package tr.kesintiharitasi.collector.source;

import java.time.Duration;
import tr.kesintiharitasi.collector.model.FeedId;

/**
 * Bir kaynagin tek bir feed'ini (planli sayfa, ariza sayfasi, veri seti...) tarar ve ortak modele cevirir.
 * Yeni kurum eklemek = yeni bir SourceCollector + fixture testi.
 *
 * <p>collect() hata firlatirsa tarama basarisiz sayilir; onceki snapshot korunur, GONE uretilmez.
 * Kaynakta gercekten kayit yoksa bos liste doner.
 */
public interface SourceCollector {

    FeedId id();

    /** Config'te aralik verilmemisse kullanilir. */
    Duration defaultInterval();

    CollectResult collect() throws Exception;
}
