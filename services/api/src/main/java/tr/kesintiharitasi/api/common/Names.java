package tr.kesintiharitasi.api.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turkce karakterden ve buyuk/kucuk harften bagimsiz anahtar: "Şişli", "ŞİŞLİ" ve "sisli" -> "SISLI".
 * Once Turkce kurallariyla buyuk harf (i -> İ, ı -> I), sonra Unicode ayristirmasiyla (NFD) isaretler atilir
 * (Ş -> S, İ -> I, Ö -> O ...), harf/rakam disi karakterler bosluk olur.
 * Ayristirma, "i" harfinin arkasina ayri bir birlesik nokta (U+0307) koyan yazimlari da duzeltiyor; Turkce
 * olmayan locale ile "İ".toLowerCase() boyle bir sonuc veriyor.
 */
public final class Names {

    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Names() {
    }

    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String upper = raw.toUpperCase(TR);
        String folded = MARKS.matcher(Normalizer.normalize(upper, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(folded).replaceAll(" ").strip();
    }
}
