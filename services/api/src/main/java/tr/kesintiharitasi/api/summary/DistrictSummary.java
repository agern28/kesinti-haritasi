package tr.kesintiharitasi.api.summary;

import java.util.Map;
import java.util.TreeMap;

/** Bir ilcedeki aktif kesinti sayilari. byType: ELECTRICITY/WATER/GAS -> sayi. */
public record DistrictSummary(
        String il,
        String ilce,
        String ilKey,
        String ilceKey,
        int total,
        int planned,
        int unplanned,
        Map<String, Integer> byType) {

    public String field() {
        return ilKey + "|" + ilceKey;
    }

    public static final class Builder {
        private final String il;
        private final String ilce;
        private final String ilKey;
        private final String ilceKey;
        private int total;
        private int planned;
        private int unplanned;
        private final Map<String, Integer> byType = new TreeMap<>();

        public Builder(String il, String ilce, String ilKey, String ilceKey) {
            this.il = il;
            this.ilce = ilce;
            this.ilKey = ilKey;
            this.ilceKey = ilceKey;
        }

        public Builder add(String type, boolean isPlanned, int count) {
            total += count;
            if (isPlanned) {
                planned += count;
            } else {
                unplanned += count;
            }
            byType.merge(type, count, Integer::sum);
            return this;
        }

        public DistrictSummary build() {
            return new DistrictSummary(il, ilce, ilKey, ilceKey, total, planned, unplanned, Map.copyOf(byType));
        }
    }
}
