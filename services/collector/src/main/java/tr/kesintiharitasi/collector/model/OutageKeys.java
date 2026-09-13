package tr.kesintiharitasi.collector.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Kimlik ve icerik hash'leri. Kurallar docs/tr/veri-modeli.md'de:
 * external_id varsa dedup_key = source:external_id, yoksa source:h:sha256(source|ilce|starts_at|sirali mahalleler).
 */
public final class OutageKeys {

    private static final char FIELD_SEP = '';
    private static final char LIST_SEP = '';

    private OutageKeys() {
    }

    public static String dedupKey(Outage o) {
        if (o.externalId() != null) {
            return o.source() + ":" + o.externalId();
        }
        String mahalleler = o.mahalleler().stream().sorted().collect(Collectors.joining(","));
        String input = String.join("|", o.source(), o.ilce(), o.startsAt().toString(), mahalleler);
        return o.source() + ":h:" + sha256(input);
    }

    /** Kaydin tum alanlarinin hash'i. Degisiklik tespiti (UPDATED) bununla yapilir. */
    public static String contentHash(Outage o) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, o.source());
        append(sb, o.externalId());
        append(sb, o.type().name());
        append(sb, Boolean.toString(o.planned()));
        append(sb, o.il());
        append(sb, o.ilce());
        append(sb, o.mahalleler().stream().sorted().collect(Collectors.joining(String.valueOf(LIST_SEP))));
        append(sb, o.startsAt().toString());
        append(sb, Objects.toString(o.endsAt(), ""));
        append(sb, o.reason());
        append(sb, o.sourceUrl());
        append(sb, Objects.toString(o.lat(), ""));
        append(sb, Objects.toString(o.lon(), ""));
        return sha256(sb.toString());
    }

    private static void append(StringBuilder sb, String value) {
        sb.append(value == null ? "" : value).append(FIELD_SEP);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
