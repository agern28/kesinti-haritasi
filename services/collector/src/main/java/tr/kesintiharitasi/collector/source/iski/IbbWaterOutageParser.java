package tr.kesintiharitasi.collector.source.iski;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * İBB Açık Veri "İstanbul'da Meydana Gelen Su Kesintileri" xlsx satirlari.
 * Basliklar: ILCE | KESİNTİ SEBEP | ARIZA KESİNTİ TARİHİ | ARIZA BİTİS TARİHİ | CALISMA YERİ | MAHALLE.
 * Yillar arasi basliklar kucuk farklar gosterebilir diye sutunlar "iceriyor mu" ile bulunur.
 * Gecmis veri: canli haritada aktif gorunmez, v2.1 mahalle karnesi icin toplanir.
 */
public class IbbWaterOutageParser {

    private static final Pattern NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");

    public record Result(List<Outage> outages, int skippedRows) {
    }

    public Result parse(List<List<String>> rows, String sourceUrl) {
        int headerIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).stream().anyMatch(c -> !c.isBlank())) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            return new Result(List.of(), 0);
        }
        List<String> header = rows.get(headerIdx).stream().map(Names::key).toList();
        int ilce = find(header, "ILCE");
        int start = find(header, "KESINTI TARIHI", "BASLANGIC");
        int end = find(header, "BITIS");
        int sebep = find(header, "SEBEP");
        int yer = find(header, "CALISMA YERI");
        int mahalle = find(header, "MAHALLE");
        if (ilce < 0 || start < 0 || mahalle < 0) {
            throw new IllegalStateException("İBB su kesintisi dosyasinda beklenen basliklar yok: " + rows.get(headerIdx));
        }
        List<Outage> out = new ArrayList<>();
        int skipped = 0;
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String ilceName = Names.ilce(get(row, ilce));
            Instant startsAt = instant(get(row, start));
            if (ilceName == null || startsAt == null) {
                if (row.stream().anyMatch(c -> !c.isBlank())) {
                    skipped++;
                }
                continue;
            }
            List<String> mahalleler = Names.mahalleler(List.of(get(row, mahalle).split(",")));
            out.add(new Outage("ISKI", null, OutageType.WATER, false, "İSTANBUL", ilceName, mahalleler, startsAt,
                    instant(get(row, end)), reason(get(row, sebep), get(row, yer)), sourceUrl, null, null));
        }
        return new Result(out, skipped);
    }

    private static int find(List<String> header, String... needles) {
        for (String needle : needles) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).contains(needle)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String get(List<String> row, int idx) {
        return idx >= 0 && idx < row.size() ? row.get(idx) : "";
    }

    private static Instant instant(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (NUMBER.matcher(s).matches()) {
                return Dates.excelSerial(Double.parseDouble(s));
            }
            return Dates.local(s, Dates.SLASHED_SECONDS, Dates.SLASHED_MINUTES, Dates.DASHED_SECONDS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String reason(String sebep, String yer) {
        String a = sebep == null ? "" : sebep.strip().replaceAll("\\s+", " ");
        String b = yer == null ? "" : yer.strip().replaceAll("\\s+", " ");
        if (!a.isEmpty() && !b.isEmpty()) {
            return a + " - " + b;
        }
        return a.isEmpty() ? (b.isEmpty() ? null : b) : a;
    }
}
