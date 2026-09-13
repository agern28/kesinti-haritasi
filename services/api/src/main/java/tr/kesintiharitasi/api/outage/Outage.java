package tr.kesintiharitasi.api.outage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API'nin dondurdugu kesinti. active: su an suruyor mu (baslamis, bitmemis, kaynaktan kalkmamis).
 * ilKey/ilceKey: Turkce karakterden bagimsiz anahtar; frontend harita sinirlariyla bununla eslestirir.
 */
public record Outage(
        UUID id,
        String source,
        String externalId,
        String type,
        boolean planned,
        String il,
        String ilce,
        String ilKey,
        String ilceKey,
        List<String> mahalleler,
        Instant startsAt,
        Instant endsAt,
        String reason,
        String sourceUrl,
        Double lat,
        Double lon,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant goneAt,
        boolean active) {
}
