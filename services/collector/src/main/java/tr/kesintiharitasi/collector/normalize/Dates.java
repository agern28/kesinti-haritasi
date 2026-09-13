package tr.kesintiharitasi.collector.normalize;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Kaynaklardaki tarih/saat bicimleri. Saat dilimi olmayan degerler Europe/Istanbul kabul edilir.
 */
public final class Dates {

    public static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /** BEDAŞ/AEDAŞ/ÇEDAŞ GetItemsData: 2026-09-10 09:00:00 */
    public static final DateTimeFormatter DASHED_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** İZSU: 10.09.2026 - 22:00 */
    public static final DateTimeFormatter DOTTED_DASH_MINUTES = DateTimeFormatter.ofPattern("dd.MM.yyyy - HH:mm");
    /** İBB xlsx: 12/02/2024 13:30:10 */
    public static final DateTimeFormatter SLASHED_SECONDS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    public static final DateTimeFormatter SLASHED_MINUTES = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private Dates() {
    }

    /** Saat dilimsiz yerel zamani Istanbul saatine gore Instant'a cevirir. Bos ise null. */
    public static Instant local(String text, DateTimeFormatter format) {
        String s = clean(text);
        if (s == null) {
            return null;
        }
        return LocalDateTime.parse(s, format).atZone(ISTANBUL).toInstant();
    }

    /** Birden fazla bicim dener; hicbiri tutmazsa DateTimeParseException. */
    public static Instant local(String text, DateTimeFormatter... formats) {
        String s = clean(text);
        if (s == null) {
            return null;
        }
        DateTimeParseException last = null;
        for (DateTimeFormatter f : formats) {
            try {
                return LocalDateTime.parse(s, f).atZone(ISTANBUL).toInstant();
            } catch (DateTimeParseException e) {
                last = e;
            }
        }
        throw last;
    }

    /**
     * ISO-8601: ofset varsa onu kullanir (2026-09-11T11:07:34.000+03:00),
     * yoksa Istanbul saati kabul eder (2026-09-11T09:00:00).
     */
    public static Instant iso(String text) {
        String s = clean(text);
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(s).atZone(ISTANBUL).toInstant();
        }
    }

    /** Excel seri tarihi (1900 sistemi): 45334.5625 gibi. */
    public static Instant excelSerial(double serial) {
        long days = (long) Math.floor(serial);
        long seconds = Math.round((serial - days) * 86_400);
        return EXCEL_EPOCH.plusDays(days).atStartOfDay().plusSeconds(seconds).atZone(ISTANBUL).toInstant();
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String s = SPACES.matcher(text.replace(' ', ' ')).replaceAll(" ").strip();
        return s.isEmpty() ? null : s;
    }
}
