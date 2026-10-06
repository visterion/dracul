package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence of the Tech-Sparplan (V54, spec 2026-10-06 §3.4, §7). Every status change is a CAS
 * ({@code WHERE id = :id AND status = :expected}): zero updated rows means another pass owns the
 * row. Nothing here opens a transaction; callers that need one wrap the calls in
 * {@code savingsPlanTransactions} (JdbcClient joins it).
 */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SavingsPlanRepository {

    private static final String IN_FLIGHT_SQL =
            "('PLACING', 'PLACED', 'CONSOLIDATING', 'UNPROTECTED', 'EMERGENCY_EXIT')";

    private final JdbcClient jdbc;

    public SavingsPlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- lease (spec §3.4) -------------------------------------------------------------------

    public boolean tryAcquireLease(String pass) {
        return jdbc.sql("""
                UPDATE savings_plan_lock SET holder = :pass, until = now() + interval '10 minutes'
                WHERE id = 1 AND until < now()
                """).param("pass", pass).update() == 1;
    }

    public boolean renewLease(String pass) {
        return jdbc.sql("""
                UPDATE savings_plan_lock SET until = now() + interval '10 minutes'
                WHERE id = 1 AND holder = :pass
                """).param("pass", pass).update() == 1;
    }

    public void releaseLease(String pass) {
        jdbc.sql("UPDATE savings_plan_lock SET until = '-infinity' WHERE id = 1 AND holder = :pass")
                .param("pass", pass).update();
    }

    /** The current holder while its lease is live, else null — logged as {@code why=lease-held-by:<uuid>}. */
    public String leaseHolder() {
        return jdbc.sql("SELECT holder FROM savings_plan_lock WHERE id = 1 AND until > now()")
                .query(String.class).optional().orElse(null);
    }

    // ---- month -------------------------------------------------------------------------------

    public SavingsMonth findMonth(String month) {
        return jdbc.sql("SELECT * FROM savings_plan_month WHERE month = :m")
                .param("m", month)
                .query((rs, n) -> new SavingsMonth(rs.getString("month"),
                        rs.getBigDecimal("month_amount_eur"),
                        (Integer) rs.getObject("candidate_count"),
                        instant(rs, "completed_at"), instant(rs, "missed_alerted_at")))
                .optional().orElse(null);
    }

    /** First writer wins: an existing amount / count is never overwritten (spec §7, R4 Minor 5). */
    public SavingsMonth ensureMonth(String month, BigDecimal amountEur, int candidateCount) {
        jdbc.sql("""
                INSERT INTO savings_plan_month (month, month_amount_eur, candidate_count)
                VALUES (:m, :a, :c)
                ON CONFLICT (month) DO UPDATE
                SET month_amount_eur = COALESCE(savings_plan_month.month_amount_eur, EXCLUDED.month_amount_eur),
                    candidate_count = COALESCE(savings_plan_month.candidate_count, EXCLUDED.candidate_count)
                """).param("m", month).param("a", amountEur).param("c", candidateCount).update();
        return findMonth(month);
    }

    /** @return true when THIS call completed the month */
    public boolean completeMonth(String month) {
        return jdbc.sql("""
                INSERT INTO savings_plan_month (month, completed_at) VALUES (:m, now())
                ON CONFLICT (month) DO UPDATE SET completed_at = now()
                WHERE savings_plan_month.completed_at IS NULL
                """).param("m", month).update() == 1;
    }

    /** @return true when THIS call set the flag — SAVINGS_PLAN_MISSED is sent exactly once */
    public boolean markMissedAlerted(String month) {
        return jdbc.sql("""
                INSERT INTO savings_plan_month (month, missed_alerted_at) VALUES (:m, now())
                ON CONFLICT (month) DO UPDATE SET missed_alerted_at = now()
                WHERE savings_plan_month.missed_alerted_at IS NULL
                """).param("m", month).update() == 1;
    }

    // ---- carry -------------------------------------------------------------------------------

    public BigDecimal carryOf(long positionId) {
        return jdbc.sql("SELECT carry_eur FROM savings_plan_carry WHERE position_id = :p")
                .param("p", positionId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    public void setCarry(long positionId, BigDecimal carryEur) {
        jdbc.sql("""
                INSERT INTO savings_plan_carry (position_id, carry_eur) VALUES (:p, :c)
                ON CONFLICT (position_id) DO UPDATE SET carry_eur = EXCLUDED.carry_eur, updated_at = now()
                """).param("p", positionId).param("c", carryEur).update();
    }

    public void addCarry(long positionId, BigDecimal deltaEur) {
        jdbc.sql("""
                INSERT INTO savings_plan_carry (position_id, carry_eur) VALUES (:p, :d)
                ON CONFLICT (position_id) DO UPDATE
                SET carry_eur = savings_plan_carry.carry_eur + EXCLUDED.carry_eur, updated_at = now()
                """).param("p", positionId).param("d", deltaEur).update();
    }

    public void deleteCarry(long positionId) {
        jdbc.sql("DELETE FROM savings_plan_carry WHERE position_id = :p").param("p", positionId).update();
    }

    /** Lazy cleanup at the start of the add stage (spec §4.2): carry of a position that is no
     *  longer OPEN or was half-sold is dropped — it can never receive savings money again. */
    public int deleteStaleCarry() {
        return jdbc.sql("""
                DELETE FROM savings_plan_carry c USING executor_position p
                WHERE p.id = c.position_id AND (p.status <> 'OPEN' OR p.trim_count > 0)
                """).update();
    }

    // ---- buys --------------------------------------------------------------------------------

    /** The intent row, written BEFORE the broker call (spec §4.4 step 1). @return the id, or null
     *  when (month, position) already has a row — a parallel pass then skips this position. */
    public Long insertPlacing(String month, long positionId, String symbol, BigDecimal qty,
            BigDecimal limitPrice, BigDecimal limitEur, BigDecimal qtyBefore, BigDecimal avgBefore,
            BigDecimal stopBefore, String tif) {
        return jdbc.sql("""
                INSERT INTO savings_plan_buy (month, position_id, symbol, qty, limit_price, limit_eur,
                    client_ref, qty_before, avg_before, stop_before, status, tif)
                VALUES (:month, :pid, :symbol, :qty, :limit, :limitEur, :ref, :qb, :ab, :sb,
                    'PLACING', :tif)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
                .param("month", month).param("pid", positionId).param("symbol", symbol)
                .param("qty", qty).param("limit", limitPrice).param("limitEur", limitEur)
                .param("ref", SavingsBuy.clientRef(positionId, month))
                .param("qb", qtyBefore).param("ab", avgBefore).param("sb", stopBefore)
                .param("tif", tif)
                .query(Long.class).optional().orElse(null);
    }

    /** A position-level skip is a row too (qty 0), so a catch-up pass never accrues twice. */
    public Long insertSkipped(String month, long positionId, String symbol, BigDecimal qtyBefore,
            BigDecimal avgBefore, BigDecimal stopBefore, String reason) {
        return jdbc.sql("""
                INSERT INTO savings_plan_buy (month, position_id, symbol, qty, client_ref,
                    qty_before, avg_before, stop_before, status, skip_reason)
                VALUES (:month, :pid, :symbol, 0, :ref, :qb, :ab, :sb, 'SKIPPED', :reason)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
                .param("month", month).param("pid", positionId).param("symbol", symbol)
                .param("ref", SavingsBuy.clientRef(positionId, month))
                .param("qb", qtyBefore).param("ab", avgBefore).param("sb", stopBefore)
                .param("reason", reason)
                .query(Long.class).optional().orElse(null);
    }

    public boolean existsForMonth(String month, long positionId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM savings_plan_buy WHERE month = :m AND position_id = :p)
                """).param("m", month).param("p", positionId).query(Boolean.class).single());
    }

    public SavingsBuy findById(long id) {
        return jdbc.sql("SELECT * FROM savings_plan_buy WHERE id = :id").param("id", id)
                .query(this::mapRow).optional().orElse(null);
    }

    public List<SavingsBuy> findInFlight() {
        return jdbc.sql("SELECT * FROM savings_plan_buy WHERE status IN " + IN_FLIGHT_SQL + " ORDER BY id")
                .query(this::mapRow).list();
    }

    public SavingsBuy findInFlightByPosition(long positionId) {
        return jdbc.sql("SELECT * FROM savings_plan_buy WHERE position_id = :p AND status IN "
                        + IN_FLIGHT_SQL + " ORDER BY id LIMIT 1")
                .param("p", positionId).query(this::mapRow).optional().orElse(null);
    }

    public Map<Long, String> inFlightStatusByPosition() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (SavingsBuy b : findInFlight()) out.putIfAbsent(b.positionId(), b.status());
        return out;
    }

    public Set<Long> positionsTouchedSince(Instant since) {
        return new HashSet<>(jdbc.sql(
                        "SELECT DISTINCT position_id FROM savings_plan_buy WHERE updated_at >= :since")
                .param("since", Timestamp.from(since)).query(Long.class).list());
    }

    public List<SavingsBuy> findByMonth(String month) {
        return jdbc.sql("SELECT * FROM savings_plan_buy WHERE month = :m ORDER BY id")
                .param("m", month).query(this::mapRow).list();
    }

    // ---- CAS transitions ---------------------------------------------------------------------

    public boolean markPlaced(long id, String entryOrderId, String childStopOrderId) {
        return jdbc.sql("""
                UPDATE savings_plan_buy
                SET status = 'PLACED', entry_order_id = :eo, child_stop_order_id = :cs, updated_at = now()
                WHERE id = :id AND status = 'PLACING'
                """).param("eo", entryOrderId).param("cs", childStopOrderId).param("id", id).update() == 1;
    }

    public boolean transition(long id, String expected, String next) {
        return jdbc.sql("""
                UPDATE savings_plan_buy SET status = :next, updated_at = now()
                WHERE id = :id AND status = :expected
                """).param("next", next).param("id", id).param("expected", expected).update() == 1;
    }

    /** Step 4 audit (spec §5.2): target qty/stop and the broker average. An UNPROTECTED row stays
     *  UNPROTECTED while it is re-targeted. */
    public boolean markConsolidating(long id, String expected, BigDecimal targetQty,
            BigDecimal targetStop, BigDecimal avgAfter) {
        return jdbc.sql("""
                UPDATE savings_plan_buy
                SET status = CASE WHEN status = 'UNPROTECTED' THEN 'UNPROTECTED' ELSE 'CONSOLIDATING' END,
                    target_qty = :tq, target_stop = :ts, avg_after = :aa, updated_at = now()
                WHERE id = :id AND status = :expected
                """).param("tq", targetQty).param("ts", targetStop).param("aa", avgAfter)
                .param("id", id).param("expected", expected).update() == 1;
    }

    /** Persisted right after the place, before any further broker call (spec §5.2 step 6, R3 M5). */
    public boolean setNewStopOrderId(long id, String expected, String newStopOrderId) {
        return jdbc.sql("""
                UPDATE savings_plan_buy SET new_stop_order_id = :ns, updated_at = now()
                WHERE id = :id AND status = :expected
                """).param("ns", newStopOrderId).param("id", id).param("expected", expected).update() == 1;
    }

    /** The window-stop de-dup marker (spec §5.2 step 3a). */
    public boolean markWindowStop(long id, BigDecimal qty) {
        return jdbc.sql("""
                UPDATE savings_plan_buy SET window_stop_qty = :q, updated_at = now()
                WHERE id = :id AND window_stop_qty IS NULL
                """).param("q", qty).param("id", id).update() == 1;
    }

    public boolean markEmergencyExit(long id, String expected, BigDecimal fillQty, BigDecimal fillPrice) {
        return jdbc.sql("""
                UPDATE savings_plan_buy
                SET status = 'EMERGENCY_EXIT', fill_qty = :fq, fill_price = :fp, updated_at = now()
                WHERE id = :id AND status = :expected
                """).param("fq", fillQty).param("fp", fillPrice).param("id", id)
                .param("expected", expected).update() == 1;
    }

    /** Any CAS into a status that carries fills; a null value keeps what the row already has. */
    public boolean finish(long id, String expected, String terminal, BigDecimal fillQty,
            BigDecimal fillPrice, BigDecimal avgAfter, BigDecimal stopAfter) {
        return jdbc.sql("""
                UPDATE savings_plan_buy
                SET status = :terminal,
                    fill_qty = COALESCE(CAST(:fq AS numeric), fill_qty),
                    fill_price = COALESCE(CAST(:fp AS numeric), fill_price),
                    avg_after = COALESCE(CAST(:aa AS numeric), avg_after),
                    stop_after = COALESCE(CAST(:sa AS numeric), stop_after),
                    updated_at = now()
                WHERE id = :id AND status = :expected
                """).param("terminal", terminal).param("fq", fillQty).param("fp", fillPrice)
                .param("aa", avgAfter).param("sa", stopAfter).param("id", id)
                .param("expected", expected).update() == 1;
    }

    // ---- mapping -----------------------------------------------------------------------------

    private SavingsBuy mapRow(ResultSet rs, int n) throws SQLException {
        return new SavingsBuy(rs.getLong("id"), rs.getString("month"), rs.getLong("position_id"),
                rs.getString("symbol"), rs.getBigDecimal("qty"), rs.getBigDecimal("limit_price"),
                rs.getBigDecimal("limit_eur"), rs.getString("client_ref"),
                rs.getString("entry_order_id"), rs.getString("child_stop_order_id"),
                rs.getBigDecimal("qty_before"), rs.getBigDecimal("avg_before"),
                rs.getBigDecimal("stop_before"), rs.getString("status"),
                rs.getString("new_stop_order_id"), rs.getString("skip_reason"),
                rs.getBigDecimal("window_stop_qty"), rs.getBigDecimal("target_qty"),
                rs.getBigDecimal("target_stop"), rs.getBigDecimal("fill_qty"),
                rs.getBigDecimal("fill_price"), rs.getBigDecimal("avg_after"),
                rs.getBigDecimal("stop_after"), rs.getString("tif"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
