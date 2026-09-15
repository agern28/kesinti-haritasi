package tr.kesintiharitasi.api.outage;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tr.kesintiharitasi.api.common.Names;
import tr.kesintiharitasi.api.summary.DistrictSummary;

/**
 * outage tablosu. JPA yerine JdbcClient: text[] kolonu ve ON CONFLICT upsert icin daha dogrudan.
 */
public class OutageRepository {

    /** Aktif kesinti: baslamis, bitmemis, kaynaktan kalkmamis. Java tarafinda da ayni kural (map). */
    static final String ACTIVE = "(starts_at <= :now and (ends_at is null or ends_at > :now) and gone_at is null)";

    /**
     * dedup_key ile upsert. Satir sadece icerik degistiyse ya da kayit kaynaktan kalkip geri geldiyse
     * guncellenir; ayni olay tekrar gelirse (at-least-once) hicbir satir donmez. Eski tarihli olay yeniyi ezmez.
     */
    private static final String UPSERT = """
            insert into outage (source, external_id, type, planned, il, ilce, il_key, ilce_key, mahalleler,
                                starts_at, ends_at, reason, source_url, lat, lon, dedup_key, content_hash,
                                first_seen_at, last_seen_at, gone_at)
            values (:source, :externalId, :type, :planned, :il, :ilce, :ilKey, :ilceKey, :mahalleler,
                    :startsAt, :endsAt, :reason, :sourceUrl, :lat, :lon, :dedupKey, :contentHash,
                    :seenAt, :seenAt, null)
            on conflict (dedup_key) do update set
                source = excluded.source, external_id = excluded.external_id, type = excluded.type,
                planned = excluded.planned, il = excluded.il, ilce = excluded.ilce, il_key = excluded.il_key,
                ilce_key = excluded.ilce_key, mahalleler = excluded.mahalleler, starts_at = excluded.starts_at,
                ends_at = excluded.ends_at, reason = excluded.reason, source_url = excluded.source_url,
                lat = excluded.lat, lon = excluded.lon, content_hash = excluded.content_hash,
                last_seen_at = excluded.last_seen_at, gone_at = null
            where excluded.last_seen_at >= outage.last_seen_at
              and (outage.content_hash <> excluded.content_hash or outage.gone_at is not null)
            returning *, (xmax = 0) as inserted
            """;

    public record Upserted(Outage outage, boolean created) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutageRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Upserted> upsert(String dedupKey, String contentHash, IncomingOutage in, Instant seenAt) {
        List<String> mahalleler = in.mahalleler() == null ? List.of() : in.mahalleler();
        return jdbc.sql(UPSERT)
                .param("source", in.source())
                .param("externalId", in.externalId(), Types.VARCHAR)
                .param("type", in.type())
                .param("planned", in.planned())
                .param("il", in.il())
                .param("ilce", in.ilce())
                .param("ilKey", Names.key(in.il()))
                .param("ilceKey", Names.key(in.ilce()))
                .param("mahalleler", mahalleler.toArray(String[]::new))
                .param("startsAt", ts(in.startsAt()))
                .param("endsAt", ts(in.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", in.reason(), Types.VARCHAR)
                .param("sourceUrl", in.sourceUrl())
                .param("lat", in.lat(), Types.DOUBLE)
                .param("lon", in.lon(), Types.DOUBLE)
                .param("dedupKey", dedupKey)
                .param("contentHash", contentHash)
                .param("seenAt", ts(seenAt))
                .query((rs, n) -> new Upserted(map(rs, clock.instant()), rs.getBoolean("inserted")))
                .optional();
    }

    /** Kaynak kaydi listeden kaldirdi. Zaten kalkmissa ya da daha yeni bir olay gelmisse degisiklik yok. */
    public Optional<Outage> markGone(String dedupKey, Instant at) {
        return jdbc.sql("""
                        update outage set gone_at = :at
                        where dedup_key = :dedupKey and gone_at is null and last_seen_at <= :at
                        returning *""")
                .param("at", ts(at))
                .param("dedupKey", dedupKey)
                .query((rs, n) -> map(rs, clock.instant()))
                .optional();
    }

    public Optional<Outage> findById(UUID id) {
        return jdbc.sql("select * from outage where id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs, clock.instant()))
                .optional();
    }

    public record Query(OutageType type, String source, String il, String ilce, Boolean active) {
    }

    /**
     * Arama filtreleri tek bir sabit sorguda: verilmeyen filtrenin parametresi null, o kosul devre disi.
     * Sorgu metni hic degismiyor, kullanicidan gelen her deger parametre olarak baglaniyor.
     * cast'ler null parametrenin tipini PostgreSQL'e soyluyor.
     */
    private static final String SEARCH_WHERE = " where (cast(:type as text) is null or type = :type)"
            + " and (cast(:source as text) is null or source = :source)"
            + " and (cast(:ilKey as text) is null or il_key = :ilKey)"
            + " and (cast(:ilceKey as text) is null or ilce_key = :ilceKey)"
            + " and (cast(:active as boolean) is null or " + ACTIVE + " = cast(:active as boolean))";
    private static final String SEARCH_COUNT = "select count(*) from outage" + SEARCH_WHERE;
    private static final String SEARCH_PAGE = "select * from outage" + SEARCH_WHERE
            + " order by starts_at desc, id limit :limit offset :offset";

    public PageResponse<Outage> search(Query q, int page, int size) {
        Instant now = clock.instant();
        Map<String, Object> params = new HashMap<>();
        params.put("type", q.type() == null ? null : q.type().name());
        params.put("source", blank(q.source()) ? null : q.source().strip().toUpperCase(java.util.Locale.ROOT));
        params.put("ilKey", blank(q.il()) ? null : Names.key(q.il()));
        params.put("ilceKey", blank(q.ilce()) ? null : Names.key(q.ilce()));
        params.put("active", q.active());
        params.put("now", ts(now));
        long total = jdbc.sql(SEARCH_COUNT).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Outage> items = jdbc.sql(SEARCH_PAGE)
                .params(params)
                .query((rs, n) -> map(rs, now))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Il/ilce bazinda aktif kesinti sayilari (harita renklendirme). */
    public List<DistrictSummary> summary(Instant now) {
        return aggregate(jdbc.sql(SUMMARY_SQL.formatted("")).param("now", ts(now)));
    }

    public Optional<DistrictSummary> summaryFor(String ilKey, String ilceKey, Instant now) {
        List<DistrictSummary> list = aggregate(jdbc.sql(SUMMARY_SQL.formatted(" and il_key = :ilKey and ilce_key = :ilceKey"))
                .param("now", ts(now))
                .param("ilKey", ilKey)
                .param("ilceKey", ilceKey));
        return list.stream().findFirst();
    }

    private static final String SUMMARY_SQL = """
            select il_key, ilce_key, min(il) as il, min(ilce) as ilce, type, planned, count(*) as c
            from outage where %s%s
            group by il_key, ilce_key, type, planned
            """.formatted(ACTIVE, "%s");

    private List<DistrictSummary> aggregate(JdbcClient.StatementSpec spec) {
        Map<String, DistrictSummary.Builder> byDistrict = new LinkedHashMap<>();
        spec.query(rs -> {
            String k = rs.getString("il_key") + "|" + rs.getString("ilce_key");
            byDistrict.computeIfAbsent(k, x -> {
                try {
                    return new DistrictSummary.Builder(rs.getString("il"), rs.getString("ilce"),
                            rs.getString("il_key"), rs.getString("ilce_key"));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).add(rs.getString("type"), rs.getBoolean("planned"), rs.getInt("c"));
        });
        return byDistrict.values().stream().map(DistrictSummary.Builder::build).toList();
    }

    /** Baslama ya da bitis saati (from, to] araliginda olan, kaynaktan kalkmamis kesintiler. */
    public List<Outage> transitions(Instant from, Instant to) {
        return jdbc.sql("""
                        select * from outage
                        where gone_at is null
                          and ((starts_at > :from and starts_at <= :to) or (ends_at > :from and ends_at <= :to))
                        order by coalesce(ends_at, starts_at)""")
                .param("from", ts(from))
                .param("to", ts(to))
                .query((rs, n) -> map(rs, to))
                .list();
    }

    static Outage map(ResultSet rs, Instant now) throws SQLException {
        Instant starts = instant(rs, "starts_at");
        Instant ends = instant(rs, "ends_at");
        Instant gone = instant(rs, "gone_at");
        Array arr = rs.getArray("mahalleler");
        List<String> mahalleler = arr == null ? List.of() : List.of((String[]) arr.getArray());
        // starts_at kolonu NOT NULL; yine de bos gelirse kesinti aktif sayilmaz (NPE yerine).
        boolean active = starts != null && !starts.isAfter(now) && (ends == null || ends.isAfter(now)) && gone == null;
        return new Outage(
                rs.getObject("id", UUID.class),
                rs.getString("source"),
                rs.getString("external_id"),
                rs.getString("type"),
                rs.getBoolean("planned"),
                rs.getString("il"),
                rs.getString("ilce"),
                rs.getString("il_key"),
                rs.getString("ilce_key"),
                mahalleler,
                starts,
                ends,
                rs.getString("reason"),
                rs.getString("source_url"),
                (Double) rs.getObject("lat"),
                (Double) rs.getObject("lon"),
                instant(rs, "first_seen_at"),
                instant(rs, "last_seen_at"),
                gone,
                active);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime o = rs.getObject(column, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
