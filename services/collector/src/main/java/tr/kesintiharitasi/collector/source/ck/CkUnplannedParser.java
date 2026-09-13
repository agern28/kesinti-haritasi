package tr.kesintiharitasi.collector.source.ck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * CK Enerji RetrieveOutages (anlik kesintiler) ayristirici.
 *
 * <p>Her satir bir trafo; ayni kesinti (OUTAGE_NO) birden fazla satirda tekrar ediyor. Sadece "Bildirimsiz"
 * satirlar (ariza) alinir; "Bildirimli" olanlar planli kesintinin devam eden hali, onlar GetItemsData'dan geliyor.
 * Satirlarda yer adi yok, sadece trafo numarasi (CBS_TM_NO) var; konum ayrica GetLocation ile bulunur.
 */
public final class CkUnplannedParser {

    /** Bir ariza ve etkiledigi trafolar. */
    public record CkFault(String outageNo, Instant reportedAt, Instant estimatedRepairAt, String message,
                          List<String> transformers) {
    }

    /** GetLocation cevabi: ham ilce adi (BURDUR_MERKEZ olabilir) ve mahalle. */
    public record TmLocation(String ilce, String mahalle) {
    }

    private CkUnplannedParser() {
    }

    public static List<CkFault> faults(JsonNode root) {
        Map<String, List<JsonNode>> byOutage = new LinkedHashMap<>();
        for (JsonNode row : root.path("Outage")) {
            if (!"Bildirimsiz".equalsIgnoreCase(CkPlannedParser.text(row, "BILDIRIM_TURU"))) {
                continue;
            }
            String no = CkPlannedParser.text(row, "OUTAGE_NO");
            if (no != null) {
                byOutage.computeIfAbsent(no, k -> new ArrayList<>()).add(row);
            }
        }
        List<CkFault> faults = new ArrayList<>();
        byOutage.forEach((no, rows) -> {
            JsonNode first = rows.get(0);
            LinkedHashSet<String> tms = new LinkedHashSet<>();
            for (JsonNode r : rows) {
                String tm = CkPlannedParser.text(r, "CBS_TM_NO");
                if (tm != null) {
                    tms.add(tm);
                }
            }
            Instant reported = Dates.iso(CkPlannedParser.text(first, "RPTD_DATE"));
            if (reported != null) {
                faults.add(new CkFault(no, reported, Dates.iso(CkPlannedParser.text(first, "EST_REPAIR_TIME")),
                        CkPlannedParser.text(first, "MESSAGE"), List.copyOf(tms)));
            }
        });
        return faults;
    }

    /** GetLocation cevabi; results dizisinde ilce/mahalle ayri nesnelerde de gelebilir. */
    public static Optional<TmLocation> location(JsonNode root) {
        String ilce = null;
        String mahalle = null;
        for (JsonNode r : root.path("results")) {
            if (ilce == null) {
                ilce = CkPlannedParser.text(r, "ilce");
            }
            if (mahalle == null) {
                mahalle = CkPlannedParser.text(r, "mahalle");
            }
        }
        return ilce == null ? Optional.empty() : Optional.of(new TmLocation(ilce, mahalle));
    }

    /**
     * Arizalari konumu bilinen trafolara gore ilce ilce Outage'a cevirir.
     * Hic trafosu konumlanamamis ariza atlanir (sonraki taramada konum cache'e girince gelir).
     * external_id "OUTAGE_NO/ILCE": ayni ariza birden fazla ilceye yayilabiliyor ve konumlar
     * taramalar arasinda parca parca bulunabiliyor, bu yuzden ilce kimligin parcasi.
     */
    public static List<Outage> toOutages(CkCompany company, List<CkFault> faults, Map<String, TmLocation> locations) {
        List<Outage> out = new ArrayList<>();
        for (CkFault f : faults) {
            Map<CkCompany.Place, LinkedHashSet<String>> byPlace = new LinkedHashMap<>();
            for (String tm : f.transformers()) {
                TmLocation loc = locations.get(tm);
                if (loc == null) {
                    continue;
                }
                CkCompany.Place place = company.resolve(loc.ilce());
                if (place == null) {
                    continue;
                }
                LinkedHashSet<String> set = byPlace.computeIfAbsent(place, k -> new LinkedHashSet<>());
                String mahalle = Names.mahalle(loc.mahalle());
                if (mahalle != null) {
                    set.add(mahalle);
                }
            }
            byPlace.forEach((place, mahalleler) -> out.add(new Outage(company.code(),
                    f.outageNo() + "/" + place.ilce(), OutageType.ELECTRICITY, false, place.il(), place.ilce(),
                    List.copyOf(mahalleler), f.reportedAt(), f.estimatedRepairAt(), f.message(),
                    company.unplannedPageUrl(), null, null)));
        }
        return out;
    }
}
