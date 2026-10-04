package tr.kesintiharitasi.collector.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tr.kesintiharitasi.collector.model.Outage;

/**
 * Kaynaklarin yazdigi ilce adini sinir verisindeki ilce adina cevirir.
 *
 * <p>Neden gerek var: haritada bir kesintinin gorunmesi icin il/ilce ikilisinin sinir dosyasindaki
 * poligonla eslesmesi lazim (frontend "IL|ILCE" kimligiyle esliyor). 2026-10-04'te veritabanindaki
 * 19.285 kaydin 174'u hicbir poligona dusmuyordu. Dort sebep vardi:
 * <ul>
 *   <li>Birlesik ilce: AEDAS "KONYAALTI / KEPEZ" yaziyor (46 kayit). Iki ilceye bolunuyor.</li>
 *   <li>Ilce yerine semt: BEDAS bazi kayitlarda ilce alanina semt yaziyor
 *       (YENIBOSNA, ZINCIRLIKUYU, KUMBURGAZ, BEYAZIT, CAGLAYAN, KILYOS, KEMERBURGAZ, HADIMKOY;
 *       110 kayit). Takma ad tablosuyla ilceye cevriliyor, semt adi mahalle listesine ekleniyor.</li>
 *   <li>Il adi oneki: CEDAS "SIVAS (MERKEZ)", "TOKAT MERKEZ" yaziyor (16 kayit). Onek atiliyor.</li>
 *   <li>MERKEZ ve KIRSAL: sinir verisinde merkez ilce ilin adini tasiyor, oraya cevriliyor.</li>
 * </ul>
 *
 * <p>Takma ad tablosu elle tutuluyor; yeni vakalar {@code make map-match} raporundan cikiyor.
 * Tarama basina bir kez, {@code ScanRunner} icinde butun kaynaklara uygulaniyor.
 */
public final class Districts {

    /** Birlesik ilce ayraclari: "KONYAALTI / KEPEZ". */
    private static final Pattern AYRAC = Pattern.compile("\\s*[/,]\\s*");
    private static final Pattern PARANTEZ = Pattern.compile("\\s*\\([^)]*\\)");
    /** Il merkezini anlatan, sinir verisinde karsiligi olmayan adlar. */
    private static final Set<String> MERKEZ_ADLARI = Set.of("MERKEZ", "KIRSAL");

    /**
     * Ilce alanina semt yazilan kayitlar: "IL|SEMT" -> gercek ilce.
     * Hepsi BEDAS (Istanbul) kayitlarindan geldi.
     */
    private static final Map<String, String> TAKMA_ADLAR = Map.ofEntries(
            Map.entry("ISTANBUL|YENIBOSNA", "BAHÇELİEVLER"),
            Map.entry("ISTANBUL|ZINCIRLIKUYU", "ŞİŞLİ"),
            Map.entry("ISTANBUL|KUMBURGAZ", "BÜYÜKÇEKMECE"),
            Map.entry("ISTANBUL|BEYAZIT", "FATİH"),
            Map.entry("ISTANBUL|CAGLAYAN", "KAĞITHANE"),
            Map.entry("ISTANBUL|KILYOS", "SARIYER"),
            Map.entry("ISTANBUL|KEMERBURGAZ", "EYÜPSULTAN"),
            Map.entry("ISTANBUL|HADIMKOY", "ARNAVUTKÖY"));

    private Districts() {
    }

    /**
     * Ilce adlarini duzeltir ve birlesik ilceleri ayri kayitlara boler.
     * Sira korunur; bolunen kayitlar orijinalin yerine sirayla girer.
     */
    public static List<Outage> fix(List<Outage> outages) {
        List<Outage> out = new ArrayList<>(outages.size());
        for (Outage o : outages) {
            List<String> ilceler = ilceler(o.il(), o.ilce());
            boolean bolundu = ilceler.size() > 1;
            for (String ilce : ilceler) {
                if (ilce.equals(o.ilce()) && !bolundu) {
                    out.add(o);
                } else {
                    out.add(yeniden(o, ilce, bolundu));
                }
            }
        }
        return out;
    }

    /** Bir ilce alanindan cikan ilce adlari. Tek ad cikarsa tek elemanli liste. */
    private static List<String> ilceler(String il, String ham) {
        Set<String> adlar = new LinkedHashSet<>();
        for (String parca : AYRAC.split(ham)) {
            String ad = tekAd(il, parca);
            if (ad != null) {
                adlar.add(ad);
            }
        }
        return adlar.isEmpty() ? List.of(ham) : new ArrayList<>(adlar);
    }

    private static String tekAd(String il, String parca) {
        String ad = Names.ilce(PARANTEZ.matcher(parca).replaceAll(" "));
        if (ad == null) {
            return null;
        }
        ad = ilOnekiniAt(il, ad);
        if (MERKEZ_ADLARI.contains(Names.key(ad))) {
            return il;
        }
        String takma = TAKMA_ADLAR.get(anahtar(il) + "|" + anahtar(ad));
        return takma != null ? takma : ad;
    }

    /** "TOKAT MERKEZ" -> "MERKEZ", "BURDUR KEMER" -> "KEMER". Tek kelimeyse dokunulmaz. */
    private static String ilOnekiniAt(String il, String ad) {
        String[] kelimeler = ad.split("\\s+");
        if (kelimeler.length > 1 && Names.key(kelimeler[0]).equals(Names.key(il))) {
            return String.join(" ", List.of(kelimeler).subList(1, kelimeler.length));
        }
        return ad;
    }

    /**
     * Ilcesi degismis kayit. Bolunduyse external_id'ye ilce ekleniyor: ayni id iki kayda dusseydi
     * ikincisi birincinin uzerine yazardi (dedup_key = source:external_id).
     * Semt ilceye cevrildiyse semt adi mahalle listesinin basina ekleniyor, bilgi kaybolmasin.
     */
    private static Outage yeniden(Outage o, String ilce, boolean bolundu) {
        String externalId = o.externalId();
        if (bolundu && externalId != null) {
            externalId = externalId + "#" + anahtar(ilce);
        }
        List<String> mahalleler = o.mahalleler();
        if (!bolundu && !Names.key(ilce).equals(Names.key(o.ilce()))) {
            List<String> ekli = new ArrayList<>(mahalleler.size() + 1);
            ekli.add(o.ilce());
            ekli.addAll(mahalleler);
            mahalleler = Names.mahalleler(ekli);
        }
        return new Outage(o.source(), externalId, o.type(), o.planned(), o.il(), ilce, mahalleler,
                o.startsAt(), o.endsAt(), o.reason(), o.sourceUrl(), o.lat(), o.lon());
    }

    /** Takma ad ve external_id eki icin bosluksuz anahtar: "GAZI OSMANPASA" -> "GAZIOSMANPASA". */
    private static String anahtar(String ad) {
        return Names.key(ad).replace(" ", "");
    }
}
