package tr.kesintiharitasi.collector.source.kcetas;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * KCETAŞ kesinti-sorgu.php cevabi (GeoJSON benzeri). Ornek ozellik:
 * {"ilce":"PINARBAŞI","adres":" SOLAKLAR MAH. PINARBAŞI KAYSERİ","tur":"Bildirimli",
 *  "baslangic":"2026-09-11T09:00:00","bitis":"2026-09-11T17:00:00"} + poligon.
 * Kaynak id vermiyor; tekillestirme hash ile. lat/lon poligonun agirlik merkezi.
 */
public class KcetasParser {

    static final String IL = "KAYSERİ";
    public static final String SOURCE_URL = "https://www.kcetas.com.tr/tr/planli-kesintiler-bakimlar";

    public List<Outage> parse(JsonNode root) {
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("KCETAŞ sorgusu basarisiz dondu");
        }
        if (root.path("sistem_bakimda").asBoolean(false)) {
            throw new IllegalStateException("KCETAŞ sistemi bakimda");
        }
        List<Outage> out = new ArrayList<>();
        for (JsonNode f : root.path("features")) {
            JsonNode p = f.path("properties");
            String ilce = Names.ilce(p.path("ilce").asString(""));
            if (ilce == null) {
                continue;
            }
            String mahalle = mahalle(p.path("adres").asString(""), ilce);
            double[] centroid = centroid(f.path("geometry"));
            out.add(new Outage("KCETAS", null, OutageType.ELECTRICITY,
                    !"Bildirimsiz".equalsIgnoreCase(p.path("tur").asString("").strip()),
                    IL, ilce, mahalle == null ? List.of() : List.of(mahalle),
                    Dates.iso(p.path("baslangic").asString(null)), Dates.iso(p.path("bitis").asString(null)),
                    null, SOURCE_URL,
                    centroid == null ? null : centroid[0], centroid == null ? null : centroid[1]));
        }
        return out;
    }

    /** " SOLAKLAR MAH. PINARBAŞI KAYSERİ" -> SOLAKLAR */
    static String mahalle(String adres, String ilce) {
        String s = Names.name(adres);
        if (s == null) {
            return null;
        }
        if (s.endsWith(" " + IL)) {
            s = s.substring(0, s.length() - IL.length() - 1);
        }
        if (s.endsWith(" " + ilce)) {
            s = s.substring(0, s.length() - ilce.length() - 1);
        }
        return Names.mahalle(s);
    }

    /** Dis halkanin nokta ortalamasi; [lat, lon]. Kapanis noktasi (ilk nokta tekrari) sayilmaz. */
    static double[] centroid(JsonNode geometry) {
        JsonNode ring = geometry.path("coordinates").path(0);
        if (!"Polygon".equals(geometry.path("type").asString("")) || !ring.isArray() || ring.size() == 0) {
            return null;
        }
        int n = ring.size();
        JsonNode firstPt = ring.get(0);
        JsonNode lastPt = ring.get(n - 1);
        if (n > 1 && firstPt.get(0).asDouble() == lastPt.get(0).asDouble()
                && firstPt.get(1).asDouble() == lastPt.get(1).asDouble()) {
            n--;
        }
        double lon = 0;
        double lat = 0;
        for (int i = 0; i < n; i++) {
            lon += ring.get(i).get(0).asDouble();
            lat += ring.get(i).get(1).asDouble();
        }
        return new double[] {lat / n, lon / n};
    }
}
