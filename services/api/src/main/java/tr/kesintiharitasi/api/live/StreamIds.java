package tr.kesintiharitasi.api.live;

/** Redis stream id'leri "zaman-sira" bicimindedir (1789285448129-0); sayisal karsilastirilir. */
final class StreamIds {

    private StreamIds() {
    }

    /** a > b ise pozitif. b null ise a her zaman daha yeni. Gecersiz id en eski sayilir. */
    static int compare(String a, String b) {
        if (b == null) {
            return a == null ? 0 : 1;
        }
        if (a == null) {
            return -1;
        }
        long[] x = parse(a);
        long[] y = parse(b);
        int c = Long.compare(x[0], y[0]);
        return c != 0 ? c : Long.compare(x[1], y[1]);
    }

    static boolean valid(String id) {
        return id != null && id.matches("\\d+-\\d+");
    }

    private static long[] parse(String id) {
        if (!valid(id)) {
            return new long[] {-1, -1};
        }
        int dash = id.indexOf('-');
        return new long[] {Long.parseLong(id.substring(0, dash)), Long.parseLong(id.substring(dash + 1))};
    }
}
