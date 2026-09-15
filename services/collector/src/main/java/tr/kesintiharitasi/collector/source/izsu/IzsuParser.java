package tr.kesintiharitasi.collector.source.izsu;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * İZSU "Arıza ve Bakım Bilgisi" sayfasi. Iki tablo sunucuda render ediliyor:
 * <ul>
 *   <li>Planli bakim: İlçe | Mahalleler | İş Adı | Kesinti Başlangıç | Kesinti Bitiş | Kısa Açıklama</li>
 *   <li>Ariza: İlçe | Mahalleler | Kesinti Süresi | Arıza Tipi | Açıklama
 *       ("15.09.2026 saat 10:49 ile 12:30 arasında")</li>
 * </ul>
 * Ayni veri mobil icin baska tablolarda tekrar ediyor; onlarin basliklari uymuyor, okunmuyor.
 * Bir bolumde kayit yoksa tablo yerine "... bulunmamaktadır." mesaji geliyor. Her bolum icin ya tablo
 * ya da bos mesaji olmali; ikisi de yoksa sayfa yapisi degismis demektir. O durumda bos liste donup
 * her seyi GONE yapmak yerine hata veriliyor.
 */
public class IzsuParser {

    public static final String SOURCE_URL = "https://izsu.gov.tr/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama";
    static final String IL = "İZMİR";

    /** "15.09.2026 saat 10:49 ile 12:30 arasında"; bitis baska gundeyse "ile 16.09.2026 saat 02:00". */
    private static final Pattern SURE = Pattern.compile(
            "(\\d{2}\\.\\d{2}\\.\\d{4})\\s+saat\\s+(\\d{1,2}:\\d{2})\\s+ile\\s+"
                    + "(?:(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(?:saat\\s+)?)?(\\d{1,2}:\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private record Table(Element element, Map<String, Integer> columns) {
    }

    public List<Outage> parse(String html) {
        Document doc = Jsoup.parse(html, SOURCE_URL);
        Table planned = null;
        Table faults = null;
        for (Element t : doc.select("table")) {
            Map<String, Integer> c = columns(t.select("thead th, tr:first-child th"));
            if (!c.containsKey("ILCE") || !c.containsKey("MAHALLELER")) {
                continue;
            }
            if (planned == null && c.containsKey("KESINTI BASLANGIC")) {
                planned = new Table(t, c);
            } else if (faults == null && c.containsKey("KESINTI SURESI")) {
                faults = new Table(t, c);
            }
        }
        List<String> empty = emptyMessages(doc);
        boolean noMaintenance = empty.stream().anyMatch(m -> m.startsWith("BAKIM"));
        boolean noFaults = empty.stream().anyMatch(m -> !m.startsWith("BAKIM"));
        if ((planned == null && !noMaintenance) || (faults == null && !noFaults)) {
            throw new IllegalStateException("İZSU kesinti tablosu bulunamadi (bakim: "
                    + (planned != null ? "tablo" : noMaintenance ? "bos" : "yok") + ", ariza: "
                    + (faults != null ? "tablo" : noFaults ? "bos" : "yok") + ")");
        }
        List<Outage> out = new ArrayList<>();
        if (planned != null) {
            out.addAll(plannedRows(planned));
        }
        if (faults != null) {
            out.addAll(faultRows(faults));
        }
        return out;
    }

    private static List<Outage> plannedRows(Table t) {
        List<Outage> out = new ArrayList<>();
        for (Element row : t.element().select("tbody tr")) {
            Elements cells = row.select("td");
            if (cells.size() < t.columns().size()) {
                continue; // "kayit yok" gibi colspan satirlar
            }
            String ilce = Names.ilce(cell(cells, t, "ILCE"));
            String start = cell(cells, t, "KESINTI BASLANGIC");
            if (ilce == null || start == null || start.isBlank()) {
                continue;
            }
            String isAdi = cell(cells, t, "IS ADI");
            // Bakim tablosunda da ariza gecen satirlar olabiliyor
            out.add(new Outage("IZSU", null, OutageType.WATER, !Names.key(isAdi).contains("ARIZA"), IL, ilce,
                    mahalleler(cells, t), Dates.local(start, Dates.DOTTED_DASH_MINUTES),
                    Dates.local(cell(cells, t, "KESINTI BITIS"), Dates.DOTTED_DASH_MINUTES),
                    reason(isAdi, cell(cells, t, "KISA ACIKLAMA")), SOURCE_URL, null, null));
        }
        return out;
    }

    private static List<Outage> faultRows(Table t) {
        List<Outage> out = new ArrayList<>();
        for (Element row : t.element().select("tbody tr")) {
            Elements cells = row.select("td");
            if (cells.size() < t.columns().size()) {
                continue;
            }
            String ilce = Names.ilce(cell(cells, t, "ILCE"));
            Instant[] range = sure(cell(cells, t, "KESINTI SURESI"));
            if (ilce == null || range == null) {
                continue;
            }
            out.add(new Outage("IZSU", null, OutageType.WATER, false, IL, ilce, mahalleler(cells, t),
                    range[0], range[1], reason(cell(cells, t, "ARIZA TIPI"), cell(cells, t, "ACIKLAMA")),
                    SOURCE_URL, null, null));
        }
        return out;
    }

    /** Ariza tablosundaki sure metni -> [baslangic, bitis]. Tarihsiz bitis baslangictan onceyse ertesi gun. */
    static Instant[] sure(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SURE.matcher(text);
        if (!m.find()) {
            return null;
        }
        LocalDate startDay = LocalDate.parse(m.group(1), DAY);
        LocalDateTime start = startDay.atTime(LocalTime.parse(m.group(2), TIME));
        LocalDate endDay = m.group(3) != null ? LocalDate.parse(m.group(3), DAY) : startDay;
        LocalDateTime end = endDay.atTime(LocalTime.parse(m.group(4), TIME));
        if (m.group(3) == null && end.isBefore(start)) {
            end = end.plusDays(1);
        }
        return new Instant[] {start.atZone(Dates.ISTANBUL).toInstant(), end.atZone(Dates.ISTANBUL).toInstant()};
    }

    /** "Bakım bilgisi bulunmamaktadır." gibi bos bolum mesajlari, anahtar olarak. Script icindeki metin sayilmaz. */
    private static List<String> emptyMessages(Document doc) {
        return doc.getElementsMatchingOwnText("(?i)bulunmamaktad").stream()
                .map(e -> Names.key(e.ownText()))
                .toList();
    }

    private static Map<String, Integer> columns(Elements headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.putIfAbsent(Names.key(headers.get(i).text()), i);
        }
        return map;
    }

    private static String cell(Elements cells, Table t, String key) {
        Integer i = t.columns().get(key);
        return i == null || i >= cells.size() ? null : cells.get(i).text().strip();
    }

    private static List<String> mahalleler(Elements cells, Table t) {
        return Names.mahalleler(List.of(mahalleText(cells.get(t.columns().get("MAHALLELER"))).split(",")));
    }

    /** Uzun mahalle listeleri hucrede kisaltilabiliyor; tam hali title niteliginde. */
    private static String mahalleText(Element cell) {
        Element titled = cell.selectFirst("[title]");
        return titled != null && !titled.attr("title").isBlank() ? titled.attr("title") : cell.text();
    }

    private static String reason(String title, String detail) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasDetail = detail != null && !detail.isBlank();
        if (hasTitle && hasDetail) {
            return title + " - " + detail;
        }
        return hasTitle ? title : (hasDetail ? detail : null);
    }
}
