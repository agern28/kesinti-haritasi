package tr.kesintiharitasi.collector.source.iski;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * İBB Açık Veri "İstanbul'da Meydana Gelen Su Kesintileri" xlsx satirlari.
 * Dosyalar yildan yila farkli semada:
 * <ul>
 *   <li>2023-2024: ILCE | KESİNTİ SEBEP | ARIZA KESİNTİ TARİHİ | ARIZA BİTİS TARİHİ | CALISMA YERİ | MAHALLE</li>
 *   <li>2022-2023: ARIZA NUMARASI | ILCE | MAHALLE | ARIZA SEBEP | SORUMLU | BASLANGIC | BITIS | SAAT_FARK | DAKIKA_FARK</li>
 * </ul>
 * Sutunlar bu yuzden basliklarda "iceriyor mu" ile bulunur. Gecmis veri: canli haritada aktif gorunmez,
 * v2.1 mahalle karnesi icin toplanir.
 *
 * <p>Veri temizligi:
 * <ul>
 *   <li>Bazi ilceler kisaltilmis (G.O.PAŞA, B.ÇEKMECE, K.ÇEKMECE); tam ada cevrilir.</li>
 *   <li>Bitis bos ya da baslangictan onceyse once SAAT_FARK/DAKIKA_FARK denenir; o da yoksa bitis = baslangic
 *       kabul edilir. Gecmis veri oldugu icin kesinti kesin bitmis, sadece suresi bilinmiyor. Bitisi bos birakmak
 *       kaydi "hala suruyor" yapardi.</li>
 *   <li>"ADALAR-STANDARTDIŞI ADRES" gibi adres bilinmiyor anlamindaki mahalle girdileri atilir.</li>
 * </ul>
 */
public class IbbWaterOutageParser {

    private static final Pattern NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");

    /** İBB dosyalarindaki kisaltilmis ilce adlari (Names.key -> tam ad). */
    static final Map<String, String> DISTRICT_ALIASES = Map.of(
            "G O PASA", "GAZİOSMANPAŞA",
            "B CEKMECE", "BÜYÜKÇEKMECE",
            "K CEKMECE", "KÜÇÜKÇEKMECE");

    public record Result(List<Outage> outages, int skippedRows, int estimatedEnds) {
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
            return new Result(List.of(), 0, 0);
        }
        List<String> header = rows.get(headerIdx).stream().map(Names::key).toList();
        int ilce = find(header, "ILCE");
        int start = find(header, "KESINTI TARIHI", "BASLANGIC");
        int end = find(header, "BITIS");
        int sebep = find(header, "SEBEP");
        int yer = find(header, "CALISMA YERI");
        int mahalle = find(header, "MAHALLE");
        int hours = find(header, "SAAT FARK");
        int minutes = find(header, "DAKIKA FARK");
        if (ilce < 0 || start < 0 || mahalle < 0) {
            throw new IllegalStateException("İBB su kesintisi dosyasinda beklenen basliklar yok: " + rows.get(headerIdx));
        }
        List<Outage> out = new ArrayList<>();
        int skipped = 0;
        int estimated = 0;
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String ilceName = district(get(row, ilce));
            Instant startsAt = instant(get(row, start));
            if (ilceName == null || startsAt == null) {
                if (row.stream().anyMatch(c -> !c.isBlank())) {
                    skipped++;
                }
                continue;
            }
            Instant endsAt = instant(get(row, end));
            if (endsAt == null || endsAt.isBefore(startsAt)) {
                endsAt = fromDuration(startsAt, get(row, hours), get(row, minutes));
                estimated++;
            }
            List<String> mahalleler = Names.mahalleler(List.of(get(row, mahalle).split(","))).stream()
                    .filter(m -> !Names.key(m).contains("STANDARTDISI"))
                    .toList();
            out.add(new Outage("ISKI", null, OutageType.WATER, false, "İSTANBUL", ilceName, mahalleler, startsAt,
                    endsAt, reason(get(row, sebep), get(row, yer)), sourceUrl, null, null));
        }
        return new Result(out, skipped, estimated);
    }

    static String district(String raw) {
        String name = Names.ilce(raw);
        return name == null ? null : DISTRICT_ALIASES.getOrDefault(Names.key(name), name);
    }

    /** SAAT_FARK/DAKIKA_FARK varsa baslangic + sure, yoksa baslangic (sure bilinmiyor). */
    private static Instant fromDuration(Instant start, String hours, String minutes) {
        try {
            long h = hours == null || hours.isBlank() ? -1 : Long.parseLong(hours.strip());
            long m = minutes == null || minutes.isBlank() ? -1 : Long.parseLong(minutes.strip());
            if (h >= 0 && m >= 0) {
                return start.plus(Duration.ofHours(h).plusMinutes(m));
            }
        } catch (NumberFormatException ignored) {
            // asagida baslangic kullaniliyor
        }
        return start;
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
