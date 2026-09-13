package tr.kesintiharitasi.api.outage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** Collector'in stream'e yazdigi payload (collector'daki Outage record'u). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingOutage(
        String source,
        String externalId,
        String type,
        boolean planned,
        String il,
        String ilce,
        List<String> mahalleler,
        Instant startsAt,
        Instant endsAt,
        String reason,
        String sourceUrl,
        Double lat,
        Double lon) {
}
