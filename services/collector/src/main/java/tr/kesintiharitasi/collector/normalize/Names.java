package tr.kesintiharitasi.collector.normalize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Il/ilce/mahalle adlarinin normalizasyonu.
 * Kurallar: bosluklar tek bosluga iner, Turkce kurallariyla buyuk harfe cevrilir (i -> İ, ı -> I),
 * mahalle adlarindaki "MAH.", "MAHALLESİ" gibi ekler atilir.
 */
public final class Names {

    public static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MAHALLE_SUFFIX =
            Pattern.compile("\\s*\\b(MAHALLESİ|MAHALLESI|MAHALLE|MAH\\.?|MH\\.?)$");

    private Names() {
    }

    /** Genel ad normalizasyonu. Bos ya da sadece bosluksa null doner. */
    public static String name(String raw) {
        if (raw == null) {
            return null;
        }
        String s = SPACES.matcher(raw.replace(' ', ' ')).replaceAll(" ").strip();
        return s.isEmpty() ? null : s.toUpperCase(TR);
    }

    public static String il(String raw) {
        return name(raw);
    }

    public static String ilce(String raw) {
        return name(raw);
    }

    /** Mahalle adi: genel normalizasyon + sondaki MAH./MAHALLESİ eki. */
    public static String mahalle(String raw) {
        String s = name(raw);
        if (s == null) {
            return null;
        }
        s = MAHALLE_SUFFIX.matcher(s).replaceAll("").strip();
        return s.isEmpty() || s.equals("-") ? null : s;
    }

    /** Mahalle listesini normalize eder, bos ve tekrar edenleri atar, sirayi korur. */
    public static List<String> mahalleler(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            String m = mahalle(r);
            if (m != null) {
                out.add(m);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Karsilastirma anahtari: Turkce karakterler ASCII'ye katlanir, harf/rakam disi karakterler bosluga doner.
     * "Bitiş Tarihi" ve "BITIS TARIHI" ayni anahtari verir. Kaynaklar arasi eslestirme ve basliklar icin.
     */
    public static String key(String raw) {
        String s = name(raw);
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(switch (c) {
                case 'Ç' -> 'C';
                case 'Ğ' -> 'G';
                case 'İ', 'I', 'Î' -> 'I';
                case 'Ö' -> 'O';
                case 'Ş' -> 'S';
                case 'Ü', 'Û' -> 'U';
                case 'Â' -> 'A';
                default -> Character.isLetterOrDigit(c) ? c : ' ';
            });
        }
        return SPACES.matcher(sb.toString()).replaceAll(" ").strip();
    }
}
