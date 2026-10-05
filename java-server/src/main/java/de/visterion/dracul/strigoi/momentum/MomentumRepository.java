package de.visterion.dracul.strigoi.momentum;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** The three V53 momentum tables (spec 2026-10-04 §5.3/§5.5). */
@Repository
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
public class MomentumRepository {

    /** One {@code momentum_ranking_snapshot} row. {@code month} is the target month (YYYY-MM),
     *  null when the run was not due. */
    public record StoredSnapshot(String runId, LocalDate asOfBarDate, String month,
            boolean rebalanceDue, String health, JsonNode payload) {}

    static final String START_MONTH = "start_month";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MomentumRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<StoredSnapshot> findSnapshot(String runId) {
        return jdbc.sql("""
                SELECT run_id, as_of_bar_date::text AS as_of, month, rebalance_due, health,
                       payload::text AS payload
                FROM momentum_ranking_snapshot WHERE run_id = :r
                """)
                .param("r", runId)
                .query(this::map)
                .optional();
    }

    /** Insert once per run ({@code ON CONFLICT DO NOTHING}). @return true when THIS call wrote it */
    public boolean insertSnapshot(String runId, LocalDate asOf, YearMonth month, boolean due,
            String health, JsonNode payload) {
        return jdbc.sql("""
                INSERT INTO momentum_ranking_snapshot
                  (run_id, as_of_bar_date, month, rebalance_due, health, payload)
                VALUES (:r, CAST(:asOf AS date), :month, :due, :health, CAST(:payload AS jsonb))
                ON CONFLICT (run_id) DO NOTHING
                """)
                .param("r", runId)
                .param("asOf", asOf == null ? null : asOf.toString())
                .param("month", month == null ? null : month.toString())
                .param("due", due)
                .param("health", health)
                .param("payload", mapper.writeValueAsString(payload))
                .update() == 1;
    }

    /** The payloads of the {@code n} most recent usable due snapshots up to {@code upTo}, one per
     *  month (the latest of each), newest month first — the carry counter (spec §4). */
    public List<JsonNode> lastDueSnapshotPayloads(YearMonth upTo, int n) {
        return jdbc.sql("""
                SELECT payload FROM (
                  SELECT DISTINCT ON (month) month, payload::text AS payload, created_at
                  FROM momentum_ranking_snapshot
                  WHERE rebalance_due AND health IN ('healthy', 'partial')
                    AND month IS NOT NULL AND month <= :upTo
                  ORDER BY month DESC, created_at DESC) s
                ORDER BY month DESC LIMIT :n
                """)
                .param("upTo", upTo.toString())
                .param("n", n)
                .query((rs, i) -> mapper.readTree(rs.getString("payload")))
                .list();
    }

    public boolean rebalanceCompleted(YearMonth month) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM momentum_rebalance WHERE month = :m)")
                .param("m", month.toString())
                .query(Boolean.class)
                .single());
    }

    /** @return true when THIS call marked the month (idempotent, {@code ON CONFLICT DO NOTHING}) */
    public boolean markRebalanced(YearMonth month, String runId) {
        return jdbc.sql("""
                INSERT INTO momentum_rebalance (month, run_id) VALUES (:m, :r)
                ON CONFLICT (month) DO NOTHING
                """)
                .param("m", month.toString())
                .param("r", runId == null ? "-" : runId)
                .update() == 1;
    }

    /** The start month, written once by the first run that asks (spec §5.5, R3 Minor 7). */
    public YearMonth startMonth(YearMonth candidate) {
        jdbc.sql("""
                INSERT INTO momentum_state (key, value) VALUES (:k, :v)
                ON CONFLICT (key) DO NOTHING
                """)
                .param("k", START_MONTH)
                .param("v", candidate.toString())
                .update();
        return YearMonth.parse(jdbc.sql("SELECT value FROM momentum_state WHERE key = :k")
                .param("k", START_MONTH)
                .query(String.class)
                .single());
    }

    private StoredSnapshot map(ResultSet rs, int n) throws SQLException {
        String asOf = rs.getString("as_of");
        return new StoredSnapshot(rs.getString("run_id"),
                asOf == null ? null : LocalDate.parse(asOf), rs.getString("month"),
                rs.getBoolean("rebalance_due"), rs.getString("health"),
                mapper.readTree(rs.getString("payload")));
    }
}
