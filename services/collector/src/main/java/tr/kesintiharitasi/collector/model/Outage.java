package tr.kesintiharitasi.collector.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Kaynaktan bagimsiz ortak kesinti modeli. Alanlar docs/tr/veri-modeli.md'deki outage tablosuyla ayni.
 * Isim alanlari parser'da normalize edilmis olarak gelir (bkz. Names).
 */
public record Outage(
        String source,
        String externalId,
        OutageType type,
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

    public Outage {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(il, "il");
        Objects.requireNonNull(ilce, "ilce");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        mahalleler = mahalleler == null ? List.of() : List.copyOf(mahalleler);
        if (externalId != null && externalId.isBlank()) {
            externalId = null;
        }
        if ((lat == null) != (lon == null)) {
            throw new IllegalArgumentException("lat ve lon ya birlikte dolu ya birlikte bos olmali");
        }
    }
}
