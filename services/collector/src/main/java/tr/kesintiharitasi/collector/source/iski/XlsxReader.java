package tr.kesintiharitasi.collector.source.iski;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Kucuk bir xlsx okuyucu: sadece ilk sayfanin hucre metinleri.
 * Apache POI bu is icin agir (bagimliliklar ve bellek; sunucu 4 GB). Biz sadece shared string ve
 * duz degerleri okuyoruz, bicim/formul yok.
 */
public final class XlsxReader {

    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;

    private XlsxReader() {
    }

    /** Satirlar, her satirda sutun sirasina gore hucre metinleri (bos hucreler ""). */
    public static List<List<String>> readFirstSheet(byte[] xlsx) throws IOException {
        Map<String, byte[]> entries = unzip(xlsx);
        try {
            String sheetPath = firstSheetPath(entries);
            byte[] sheet = entries.get(sheetPath);
            if (sheet == null) {
                throw new IOException("xlsx icinde sayfa yok: " + sheetPath);
            }
            List<String> shared = sharedStrings(entries.get("xl/sharedStrings.xml"));
            return rows(sheet, shared);
        } catch (XMLStreamException e) {
            throw new IOException("xlsx okunamadi", e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("xl/") && name.endsWith(".xml") || name.endsWith(".rels")) {
                    byte[] data = zip.readNBytes((int) MAX_ENTRY_BYTES + 1);
                    if (data.length > MAX_ENTRY_BYTES) {
                        throw new IOException("xlsx girdisi cok buyuk: " + name);
                    }
                    entries.put(name, data);
                }
            }
        }
        return entries;
    }

    private static String firstSheetPath(Map<String, byte[]> entries) throws XMLStreamException, IOException {
        String relId = null;
        XMLStreamReader r = reader(entries.get("xl/workbook.xml"));
        while (r.hasNext() && relId == null) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                relId = r.getAttributeValue(REL_NS, "id");
            }
        }
        if (relId == null) {
            return "xl/worksheets/sheet1.xml";
        }
        r = reader(entries.get("xl/_rels/workbook.xml.rels"));
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                    && relId.equals(r.getAttributeValue(null, "Id"))) {
                String target = r.getAttributeValue(null, "Target");
                return target.startsWith("/") ? target.substring(1) : "xl/" + target;
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException, IOException {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        XMLStreamReader r = reader(xml);
        StringBuilder current = null;
        int phoneticDepth = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> current = new StringBuilder();
                    case "rPh" -> phoneticDepth++;
                    case "t" -> {
                        if (current != null && phoneticDepth == 0) {
                            current.append(r.getElementText());
                        }
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if ("si".equals(r.getLocalName()) && current != null) {
                    out.add(current.toString());
                    current = null;
                } else if ("rPh".equals(r.getLocalName())) {
                    phoneticDepth--;
                }
            }
        }
        return out;
    }

    private static List<List<String>> rows(byte[] sheet, List<String> shared) throws XMLStreamException, IOException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = reader(sheet);
        List<String> row = null;
        String cellRef = null;
        String cellType = null;
        String value = null;
        int nextCol = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        row = new ArrayList<>();
                        nextCol = 0;
                    }
                    case "c" -> {
                        cellRef = r.getAttributeValue(null, "r");
                        cellType = r.getAttributeValue(null, "t");
                        value = null;
                    }
                    case "v" -> value = r.getElementText();
                    case "t" -> {
                        if ("inlineStr".equals(cellType)) {
                            value = (value == null ? "" : value) + r.getElementText();
                        }
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if ("c".equals(r.getLocalName()) && row != null) {
                    int col = cellRef != null ? column(cellRef) : nextCol;
                    while (row.size() < col) {
                        row.add("");
                    }
                    String text = value == null ? "" : value;
                    if ("s".equals(cellType) && !text.isEmpty()) {
                        text = shared.get(Integer.parseInt(text.strip()));
                    }
                    row.add(text);
                    nextCol = col + 1;
                } else if ("row".equals(r.getLocalName()) && row != null) {
                    rows.add(row);
                    row = null;
                }
            }
        }
        return rows;
    }

    /** "C12" -> 2 */
    static int column(String ref) {
        int col = 0;
        for (char ch : ref.toCharArray()) {
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            col = col * 26 + (ch - 'A' + 1);
        }
        return col - 1;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException, IOException {
        if (xml == null) {
            throw new IOException("xlsx icinde beklenen dosya yok");
        }
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(xml);
        return f.createXMLStreamReader(in);
    }
}
