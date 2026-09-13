package tr.kesintiharitasi.collector.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutageKeysTest {

    private static Outage outage(String externalId, List<String> mahalleler, String start, String reason) {
        return new Outage("ISKI", externalId, OutageType.WATER, false, "İSTANBUL", "ADALAR", mahalleler,
                Instant.parse(start), null, reason, "https://example.org", null, null);
    }

    @Test
    void kaynakIdVarsaAnahtarOnunla() {
        assertThat(OutageKeys.dedupKey(outage("123", List.of("MADEN"), "2024-02-12T10:30:00Z", "x")))
                .isEqualTo("ISKI:123");
    }

    @Test
    void kaynakIdYoksaHash() {
        String a = OutageKeys.dedupKey(outage(null, List.of("MADEN", "NİZAM"), "2024-02-12T10:30:00Z", "x"));
        String b = OutageKeys.dedupKey(outage(null, List.of("NİZAM", "MADEN"), "2024-02-12T10:30:00Z", "baska sebep"));
        String c = OutageKeys.dedupKey(outage(null, List.of("MADEN", "NİZAM"), "2024-02-12T11:30:00Z", "x"));
        assertThat(a).startsWith("ISKI:h:").hasSize("ISKI:h:".length() + 64);
        assertThat(a).as("mahalle sirasi ve sebep kimligi degistirmez").isEqualTo(b);
        assertThat(a).as("baslangic kimligin parcasi").isNotEqualTo(c);
    }

    @Test
    void icerikHashiHerAlaniKapsar() {
        Outage base = outage("1", List.of("MADEN", "NİZAM"), "2024-02-12T10:30:00Z", "sebep");
        assertThat(OutageKeys.contentHash(base))
                .isEqualTo(OutageKeys.contentHash(outage("1", List.of("NİZAM", "MADEN"), "2024-02-12T10:30:00Z", "sebep")))
                .isNotEqualTo(OutageKeys.contentHash(outage("1", List.of("MADEN", "NİZAM"), "2024-02-12T10:30:00Z", "yeni")));
    }

    @Test
    void latLonBirlikte() {
        assertThatThrownBy(() -> new Outage("X", null, OutageType.GAS, true, "İ", "İ", List.of(),
                Instant.EPOCH, null, null, "u", 41.0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
