package tr.kesintiharitasi.collector.source.ck;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * CK Enerji altyapisini kullanan dagitim sirketleri. Hepsinde ayni iki kaynak var:
 * planli kesintiler icin sirketin sitesinde GET /GetItemsData, arizalar icin kesintiapi.ckenerji.com.tr.
 * Ayrinti: docs/tr/01-kaynak-taramasi.md.
 */
public enum CkCompany {

    BEDAS("BEDAS", "www.bedas.com.tr",
            "https://www.bedas.com.tr/elektrik-kesintisi-sorgulama",
            "https://kesinti.bedas.com.tr/",
            provinces("İSTANBUL", List.of())),

    AEDAS("AEDAS", "www.akdenizedas.com.tr",
            "https://www.akdenizedas.com.tr/elektrik-kesintisi",
            "https://kesinti.akdenizedas.com.tr/",
            provinces(
                    "ANTALYA", List.of("AKSEKİ", "AKSU", "ALANYA", "DEMRE", "DÖŞEMEALTI", "ELMALI", "FİNİKE", "GAZİPAŞA",
                            "GÜNDOĞMUŞ", "İBRADI", "KAŞ", "KEMER", "KEPEZ", "KONYAALTI", "KORKUTELİ", "KUMLUCA",
                            "MANAVGAT", "MURATPAŞA", "SERİK"),
                    "BURDUR", List.of("AĞLASUN", "ALTINYAYLA", "BUCAK", "ÇAVDIR", "ÇELTİKÇİ", "GÖLHİSAR", "KARAMANLI",
                            "KEMER", "TEFENNİ", "YEŞİLOVA", "MERKEZ"),
                    "ISPARTA", List.of("AKSU", "ATABEY", "EĞİRDİR", "GELENDOST", "GÖNEN", "KEÇİBORLU", "SENİRKENT",
                            "SÜTÇÜLER", "ŞARKİKARAAĞAÇ", "ULUBORLU", "YALVAÇ", "YENİŞARBADEMLİ", "MERKEZ"))),

    CEDAS("CEDAS", "www.cedas.com.tr",
            "https://www.cedas.com.tr/elektrik-kesintisi",
            "https://www.cedas.com.tr/elektrik-kesintisi",
            provinces(
                    "SİVAS", List.of("AKINCILAR", "ALTINYAYLA", "DİVRİĞİ", "DOĞANŞAR", "GEMEREK", "GÖLOVA", "GÜRÜN",
                            "HAFİK", "İMRANLI", "KANGAL", "KOYULHİSAR", "SUŞEHRİ", "ŞARKIŞLA", "ULAŞ", "YILDIZELİ",
                            "ZARA", "MERKEZ"),
                    "TOKAT", List.of("ALMUS", "ARTOVA", "BAŞÇİFTLİK", "ERBAA", "NİKSAR", "PAZAR", "REŞADİYE",
                            "SULUSARAY", "TURHAL", "YEŞİLYURT", "ZİLE", "MERKEZ"),
                    "YOZGAT", List.of("AKDAĞMADENİ", "AYDINCIK", "BOĞAZLIYAN", "ÇANDIR", "ÇAYIRALAN", "ÇEKEREK",
                            "KADIŞEHRİ", "SARAYKENT", "SARIKAYA", "SORGUN", "ŞEFAATLİ", "YENİFAKILI", "YERKÖY",
                            "MERKEZ")));

    private static final String API = "https://kesintiapi.ckenerji.com.tr/";

    /** Normalize il adi ve ilce. */
    public record Place(String il, String ilce) {
    }

    private final String code;
    private final String host;
    private final String plannedPageUrl;
    private final String unplannedPageUrl;
    /** il -> o ilin ilce anahtarlari (Names.key). Ilk il, belirsiz durumda varsayilan. */
    private final Map<String, Set<String>> provinces;

    CkCompany(String code, String host, String plannedPageUrl, String unplannedPageUrl,
              Map<String, Set<String>> provinces) {
        this.code = code;
        this.host = host;
        this.plannedPageUrl = plannedPageUrl;
        this.unplannedPageUrl = unplannedPageUrl;
        this.provinces = provinces;
    }

    public String code() {
        return code;
    }

    public String plannedUrl() {
        return "https://" + host + "/GetItemsData";
    }

    public String unplannedUrl() {
        return API + code + "/RetrieveOutages";
    }

    public String locationUrl(String transformerNo) {
        return API + code + "/GetLocation?tmno=" + transformerNo;
    }

    /** Planli kesintide kullaniciya gosterilen orijinal sayfa. */
    public String plannedPageUrl() {
        return plannedPageUrl;
    }

    public String unplannedPageUrl() {
        return unplannedPageUrl;
    }

    /**
     * GetLocation'in verdigi ilce adindan il ve ilceyi cikarir.
     * API birden fazla ilde olan adlari "BURDUR_MERKEZ" gibi il onekiyle veriyor.
     * Onek yoksa ilce, sirketin illerindeki ilce listesinde aranir; birden fazla ilde varsa (AKSU, KEMER)
     * sirketin ana ili (listede ilk) kabul edilir.
     */
    public Place resolve(String rawIlce) {
        String name = Names.ilce(rawIlce);
        if (name == null) {
            return null;
        }
        int underscore = name.indexOf('_');
        if (underscore > 0) {
            String ilPart = name.substring(0, underscore);
            String ilcePart = Names.ilce(name.substring(underscore + 1).replace('_', ' '));
            return new Place(canonicalProvince(ilPart), ilcePart);
        }
        String first = provinces.keySet().iterator().next();
        if (provinces.size() == 1) {
            return new Place(first, name);
        }
        String key = Names.key(name);
        List<String> matches = provinces.entrySet().stream()
                .filter(e -> e.getValue().contains(key))
                .map(Map.Entry::getKey)
                .toList();
        return new Place(matches.size() == 1 ? matches.get(0) : first, name);
    }

    private String canonicalProvince(String raw) {
        String key = Names.key(raw);
        return provinces.keySet().stream()
                .filter(il -> Names.key(il).equals(key))
                .findFirst()
                .orElse(Names.il(raw));
    }

    private static Map<String, Set<String>> provinces(Object... ilAndDistricts) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < ilAndDistricts.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> districts = (List<String>) ilAndDistricts[i + 1];
            map.put((String) ilAndDistricts[i], districts.stream().map(Names::key).collect(Collectors.toUnmodifiableSet()));
        }
        return map;
    }
}
