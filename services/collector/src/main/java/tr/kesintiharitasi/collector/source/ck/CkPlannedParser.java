package tr.kesintiharitasi.collector.source.ck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * CK Enerji GetItemsData (planli kesintiler) ayristirici.
 *
 * <p>Mahalleler ayri alan olarak gelmiyor, mesaj metninde. Iki bicim var:
 * <ul>
 *   <li>BEDAŞ: "İSTANBUL ESENLER ilce MERKEZ-ORUÇREİS mah ALBAYRAK sk / TURGUT REİS mah 495. sk  bölgelerinde ..."</li>
 *   <li>AEDAŞ/ÇEDAŞ: "ANTALYA,AKSU,MERKEZ ALTINTAŞ Mah. 31225,...;ANTALYA,MURATPAŞA,... bölgelerinde ..."
 *       ve bazen onek olmadan serbest liste ("ÖZÜKAVAK KASABASI, KURTAĞILLI, ... KÖYLERİ bölgelerinde").</li>
 * </ul>
 * Kayit birden fazla ilceye yayiliyorsa (county2/county3) her ilce icin ayri Outage uretilir,
 * external_id "id/ILCE" olur.
 */
public class CkPlannedParser {

    private static final String REGION_END = " bölgelerinde";
    private static final Pattern BEDAS_SEGMENT = Pattern.compile("^(.*?)\\s+mah\\b.*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MAH_MARK = Pattern.compile("^(.*?)\\s+(MAHALLESİ|MAH\\.?|MH\\.?)(\\s.*)?$", Pattern.DOTALL);

    public List<Outage> parse(JsonNode root, CkCompany company) {
        List<Outage> out = new ArrayList<>();
        for (JsonNode item : root) {
            JsonNode po = item.path("plannedOutage");
            if (po.isMissingNode() || po.isNull()) {
                continue;
            }
            String id = text(po, "id");
            if (id == null) {
                id = text(item, "id");
            }
            List<CkCompany.Place> places = places(po);
            if (places.isEmpty()) {
                continue;
            }
            Map<String, List<String>> mahalleByIlce = mahalleler(text(po, "message"), places);
            Instant start = Dates.local(text(po, "startDateTime"), Dates.DASHED_SECONDS);
            Instant end = Dates.local(text(po, "endDateTime"), Dates.DASHED_SECONDS);
            if (start == null) {
                continue;
            }
            Double lat = coordinate(text(po, "lat"));
            Double lon = coordinate(text(po, "lon"));
            if (lat == null || lon == null) {
                lat = null;
                lon = null;
            }
            for (CkCompany.Place p : places) {
                String externalId = places.size() == 1 ? id : id + "/" + p.ilce();
                out.add(new Outage(company.code(), externalId, OutageType.ELECTRICITY, true, p.il(), p.ilce(),
                        mahalleByIlce.getOrDefault(p.ilce(), List.of()), start, end, text(po, "reason"),
                        company.plannedPageUrl(), lat, lon));
            }
        }
        return out;
    }

    /** county, county2, county3 alanlarindan tekrarsiz (il, ilce) listesi. */
    private static List<CkCompany.Place> places(JsonNode po) {
        List<CkCompany.Place> places = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String firstIl = null;
        for (String suffix : new String[] {"", "2", "3"}) {
            String il = Names.il(text(po, "city" + suffix));
            String ilce = Names.ilce(text(po, "county" + suffix));
            if (firstIl == null) {
                firstIl = il;
            }
            if (ilce != null && seen.add(ilce)) {
                places.add(new CkCompany.Place(il != null ? il : firstIl, ilce));
            }
        }
        return places;
    }

    static Map<String, List<String>> mahalleler(String message, List<CkCompany.Place> places) {
        String msg = message == null ? "" : message;
        int end = msg.indexOf(REGION_END);
        String body = end >= 0 ? msg.substring(0, end) : msg;
        return body.contains(" ilce ") ? bedasStyle(body, places) : commaStyle(body, places);
    }

    private static Map<String, List<String>> bedasStyle(String body, List<CkCompany.Place> places) {
        Map<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        String currentIlce = places.get(0).ilce();
        for (String segment : body.split(" / ")) {
            String s = segment;
            int ilceIdx = s.lastIndexOf(" ilce ");
            if (ilceIdx >= 0) {
                String prefix = Names.name(s.substring(0, ilceIdx));
                for (CkCompany.Place p : places) {
                    if (prefix != null && prefix.endsWith(p.ilce())) {
                        currentIlce = p.ilce();
                    }
                }
                s = s.substring(ilceIdx + " ilce ".length());
            }
            Matcher m = BEDAS_SEGMENT.matcher(s.strip());
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).strip();
            int dash = name.indexOf('-');
            if (dash >= 0) {
                // "MERKEZ-ORUÇREİS": ilk kisim belde/koy, mahalle tireden sonrasi. "-" tek basina bos kayit.
                name = name.substring(dash + 1);
            }
            String mahalle = Names.mahalle(name);
            if (mahalle != null) {
                result.computeIfAbsent(currentIlce, k -> new LinkedHashSet<>()).add(mahalle);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        result.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    private static Map<String, List<String>> commaStyle(String body, List<CkCompany.Place> places) {
        Map<String, List<String>> itemsByIlce = new LinkedHashMap<>();
        Set<String> provinceKeys = new LinkedHashSet<>();
        places.forEach(p -> provinceKeys.add(Names.key(p.il())));
        for (String group : body.split(";")) {
            List<String> items = new ArrayList<>();
            for (String item : group.split(",")) {
                if (!item.isBlank()) {
                    items.add(item.strip());
                }
            }
            String ilce = places.get(0).ilce();
            if (items.size() >= 2 && provinceKeys.contains(Names.key(items.get(0)))) {
                String second = Names.key(items.get(1));
                for (CkCompany.Place p : places) {
                    if (Names.key(p.ilce()).equals(second)) {
                        ilce = p.ilce();
                        items = items.subList(2, items.size());
                        break;
                    }
                }
            }
            itemsByIlce.computeIfAbsent(ilce, k -> new ArrayList<>()).addAll(items);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        itemsByIlce.forEach((ilce, items) -> out.put(ilce, placeNames(items)));
        return out;
    }

    /**
     * "MERKEZ BAYRALAR Mah. X Sk." -> BAYRALAR (guvenilir, "Mah." isaretli).
     * "MERKEZ KARAMIK" -> KARAMIK (aday). Aday, guvenilir bir mahalleyle basliyorsa sokak kalintisidir, atilir
     * ("MERKEZ DUACI 9035" -> DUACI zaten var).
     */
    private static List<String> placeNames(List<String> items) {
        LinkedHashSet<String> reliable = new LinkedHashSet<>();
        List<String> candidates = new ArrayList<>();
        for (String item : items) {
            // ÇEDAŞ bazen yer adinin arkasina trafo notu ekliyor: "...KÖYLERİ<TAB>T_TRANSFORMATOR_DAGITIM : ..."
            String part = item.split("\t", 2)[0];
            int trafo = part.toUpperCase(Names.TR).indexOf("T_TRANSFORMATOR");
            if (trafo >= 0) {
                part = part.substring(0, trafo);
            }
            if (part.contains(":") || part.toUpperCase(Names.TR).contains("TRANSFORMATOR")) {
                continue;
            }
            String up = Names.name(part);
            if (up == null) {
                continue;
            }
            if (up.startsWith("MERKEZ ")) {
                up = up.substring("MERKEZ ".length());
            } else if (up.startsWith("KÖY ")) {
                up = up.substring("KÖY ".length());
            }
            Matcher m = MAH_MARK.matcher(up);
            if (m.matches()) {
                String mahalle = Names.mahalle(m.group(1));
                if (mahalle != null) {
                    reliable.add(mahalle);
                }
            } else {
                for (String piece : up.split("-")) {
                    if (!piece.isBlank()) {
                        candidates.add(piece.strip());
                    }
                }
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(reliable);
        for (String c : candidates) {
            boolean streetRemnant = reliable.stream().anyMatch(r -> c.equals(r) || c.startsWith(r + " "));
            String mahalle = Names.mahalle(c);
            if (!streetRemnant && mahalle != null) {
                out.add(mahalle);
            }
        }
        return List.copyOf(out);
    }

    private static Double coordinate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.strip());
            return v == 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s.strip();
    }
}
