package tr.kesintiharitasi.collector.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

class DatesTest {

    @Test
    void saatDilimsizDegerIstanbulSaati() {
        assertThat(Dates.local("2026-09-10 09:00:00", Dates.DASHED_SECONDS))
                .isEqualTo(Instant.parse("2026-09-10T06:00:00Z"));
        assertThat(Dates.local("10.09.2026 - 22:00", Dates.DOTTED_DASH_MINUTES))
                .isEqualTo(Instant.parse("2026-09-10T19:00:00Z"));
        assertThat(Dates.local("12/02/2024 13:30:10", Dates.SLASHED_SECONDS))
                .isEqualTo(Instant.parse("2024-02-12T10:30:10Z"));
        assertThat(Dates.local("  10.09.2026   -  22:00 ", Dates.DOTTED_DASH_MINUTES))
                .isEqualTo(Instant.parse("2026-09-10T19:00:00Z"));
    }

    @Test
    void bosDegerNull() {
        assertThat(Dates.local(" ", Dates.DASHED_SECONDS)).isNull();
        assertThat(Dates.iso(null)).isNull();
    }

    @Test
    void birdenFazlaBicimDenenir() {
        assertThat(Dates.local("12/02/2024 13:30", Dates.SLASHED_SECONDS, Dates.SLASHED_MINUTES))
                .isEqualTo(Instant.parse("2024-02-12T10:30:00Z"));
        assertThatThrownBy(() -> Dates.local("dun", Dates.SLASHED_SECONDS, Dates.SLASHED_MINUTES))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void isoOfsetliVeOfsetsiz() {
        assertThat(Dates.iso("2026-09-11T11:07:34.000+03:00")).isEqualTo(Instant.parse("2026-09-11T08:07:34Z"));
        assertThat(Dates.iso("2026-09-11T09:00:00")).isEqualTo(Instant.parse("2026-09-11T06:00:00Z"));
    }

    @Test
    void excelSeriTarihi() {
        assertThat(Dates.excelSerial(45334.5625)).isEqualTo(Instant.parse("2024-02-12T10:30:00Z"));
    }
}
