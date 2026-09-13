package tr.kesintiharitasi.collector.support;

import java.time.Instant;
import java.util.List;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;

public final class TestOutages {

    private TestOutages() {
    }

    public static Outage outage(String externalId, String ilce, String reason) {
        return new Outage("TEST", externalId, OutageType.ELECTRICITY, true, "İSTANBUL", ilce, List.of("MERKEZ"),
                Instant.parse("2026-09-13T06:00:00Z"), Instant.parse("2026-09-13T14:00:00Z"), reason,
                "https://example.org/kesinti", null, null);
    }
}
