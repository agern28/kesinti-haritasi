package tr.kesintiharitasi.collector.source.izsu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import tr.kesintiharitasi.collector.model.Outage;
import tr.kesintiharitasi.collector.model.OutageType;
import tr.kesintiharitasi.collector.normalize.Dates;
import tr.kesintiharitasi.collector.normalize.Names;

/**
 * İZSU "Arıza ve Bakım Bilgisi" sayfasi. Tablo sunucuda render ediliyor:
 * İlçe | Mahalleler | İş Adı | Kesinti Başlangıç | Kesinti Bitiş | Kısa Açıklama.
 * Ayni veri mobil icin ikinci bir tabloda tekrar ediyor; basliklari uyan ilk tablo okunur.
 * Sayfa planli calisma ile arizayi birlikte veriyor; is adinda "arıza" geciyorsa planned=false.
 */
public class IzsuParser {

    public static final String SOURCE_URL = "https://izsu.gov.tr/bilgi-merkezi/ariza-ve-bakim-bilgisi-sorgulama";
    static final String IL = "İZMİR";

    public List<Outage> parse(String html) {
        Document doc = Jsoup.parse(html, SOURCE_URL);
        Element table = null;
        Map<String, Integer> columns = null;
        for (Element t : doc.select("table")) {
            Map<String, Integer> c = columns(t.select("thead th, tr:first-child th"));
            if (c.containsKey("ILCE") && c.containsKey("MAHALLELER")) {
                table = t;
                columns = c;
                break;
            }
        }
        if (table == null) {
            // Sayfa yapisi degismis: bos liste donup her seyi GONE yapmak yerine hata veriyoruz.
            throw new IllegalStateException("İZSU kesinti tablosu bulunamadi");
        }
        List<Outage> out = new ArrayList<>();
        for (Element row : table.select("tbody tr")) {
            Elements cells = row.select("td");
            if (cells.size() < columns.size()) {
                continue; // "kayit yok" gibi colspan satirlar
            }
            String ilce = Names.ilce(cell(cells, columns, "ILCE"));
            String start = cell(cells, columns, "KESINTI BASLANGIC");
            if (ilce == null || start == null || start.isBlank()) {
                continue;
            }
            String isAdi = cell(cells, columns, "IS ADI");
            String aciklama = cell(cells, columns, "KISA ACIKLAMA");
            List<String> mahalleler = Names.mahalleler(List.of(mahalleText(cells.get(columns.get("MAHALLELER"))).split(",")));
            out.add(new Outage("IZSU", null, OutageType.WATER, !Names.key(isAdi).contains("ARIZA"), IL, ilce,
                    mahalleler, Dates.local(start, Dates.DOTTED_DASH_MINUTES),
                    Dates.local(cell(cells, columns, "KESINTI BITIS"), Dates.DOTTED_DASH_MINUTES),
                    reason(isAdi, aciklama), SOURCE_URL, null, null));
        }
        return out;
    }

    private static Map<String, Integer> columns(Elements headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.putIfAbsent(Names.key(headers.get(i).text()), i);
        }
        return map;
    }

    private static String cell(Elements cells, Map<String, Integer> columns, String key) {
        Integer i = columns.get(key);
        return i == null || i >= cells.size() ? null : cells.get(i).text().strip();
    }

    /** Uzun mahalle listeleri hucrede kisaltilabiliyor; tam hali title niteliginde. */
    private static String mahalleText(Element cell) {
        Element titled = cell.selectFirst("[title]");
        return titled != null && !titled.attr("title").isBlank() ? titled.attr("title") : cell.text();
    }

    private static String reason(String isAdi, String aciklama) {
        boolean hasIs = isAdi != null && !isAdi.isBlank();
        boolean hasAciklama = aciklama != null && !aciklama.isBlank();
        if (hasIs && hasAciklama) {
            return isAdi + " - " + aciklama;
        }
        return hasIs ? isAdi : (hasAciklama ? aciklama : null);
    }
}
