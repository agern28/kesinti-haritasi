package tr.kesintiharitasi.api.outage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tr.kesintiharitasi.api.support.IntegrationTest;

/** Upsert kurallari: tekrar gelen olay satiri degistirmez, eski olay yeniyi ezmez, GONE ve geri gelis. */
class OutageRepositoryTest extends IntegrationTest {

    @Autowired
    OutageRepository repository;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static IncomingOutage outage(String ilce, String reason, Instant start, Instant end) {
        return new IncomingOutage("TEST", null, "ELECTRICITY", true, "İSTANBUL", ilce, List.of("MERKEZ", "ÇARŞI"),
                start, end, reason, "https://example.org", 41.0, 29.0);
    }

    @Test
    void upsertKurallari() {
        String key = unique("TEST:h:");
        String ilce = unique("ILCE");
        IncomingOutage v1 = outage(ilce, "ilk", NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        var created = repository.upsert(key, "h1", v1, NOW);
        assertThat(created).hasValueSatisfying(u -> {
            assertThat(u.created()).isTrue();
            assertThat(u.outage().mahalleler()).containsExactly("MERKEZ", "ÇARŞI");
            assertThat(u.outage().ilceKey()).isEqualTo(ilce);
            assertThat(u.outage().active()).isTrue();
            assertThat(u.outage().lat()).isEqualTo(41.0);
        });

        assertThat(repository.upsert(key, "h1", v1, NOW.plusSeconds(10))).as("ayni icerik").isEmpty();

        IncomingOutage v2 = outage(ilce, "ikinci", NOW.minusSeconds(3600), NOW.plusSeconds(7200));
        assertThat(repository.upsert(key, "h2", v2, NOW.plusSeconds(20)))
                .hasValueSatisfying(u -> assertThat(u.created()).isFalse());

        IncomingOutage stale = outage(ilce, "eski", NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        assertThat(repository.upsert(key, "h0", stale, NOW.minusSeconds(60))).as("eski tarihli olay").isEmpty();

        assertThat(repository.markGone(key, NOW.plusSeconds(30)))
                .hasValueSatisfying(o -> {
                    assertThat(o.goneAt()).isNotNull();
                    assertThat(o.active()).isFalse();
                });
        assertThat(repository.markGone(key, NOW.plusSeconds(40))).as("ikinci GONE").isEmpty();

        assertThat(repository.upsert(key, "h2", v2, NOW.plusSeconds(50))).as("geri geldi")
                .hasValueSatisfying(u -> {
                    assertThat(u.created()).isFalse();
                    assertThat(u.outage().goneAt()).isNull();
                    assertThat(u.outage().reason()).isEqualTo("ikinci");
                });
        assertThat(rowCount(key)).isEqualTo(1);
    }

    @Test
    void filtrelerTurkceKarakterdenBagimsiz() {
        String ilce = unique("ŞİŞLİ ");
        repository.upsert(unique("TEST:h:"), "a", outage(ilce, "suren", NOW.minusSeconds(60), NOW.plusSeconds(600)), NOW);
        repository.upsert(unique("TEST:h:"), "b", outage(ilce, "gelecek", NOW.plusSeconds(3600), NOW.plusSeconds(7200)), NOW);
        repository.upsert(unique("TEST:h:"), "c", outage(ilce, "bitmis", NOW.minusSeconds(7200), NOW.minusSeconds(3600)), NOW);

        String lower = ilce.toLowerCase(java.util.Locale.forLanguageTag("tr")).replace("ş", "s").replace("i", "ı");
        PageResponse<Outage> all = repository.search(new OutageRepository.Query(null, null, "istanbul", ilce.toLowerCase(), null), 0, 10);
        assertThat(all.total()).isEqualTo(3);
        assertThat(repository.search(new OutageRepository.Query(OutageType.ELECTRICITY, "test", "İSTANBUL", ilce, true), 0, 10).items())
                .singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("suren"));
        assertThat(repository.search(new OutageRepository.Query(OutageType.WATER, null, null, ilce, null), 0, 10).total()).isZero();
        assertThat(repository.search(new OutageRepository.Query(null, null, null, ilce, false), 0, 10).total()).isEqualTo(2);

        PageResponse<Outage> page = repository.search(new OutageRepository.Query(null, null, null, ilce, null), 1, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(1);
        assertThat(lower).isNotBlank();
    }
}
