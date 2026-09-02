package de.visterion.dracul.depot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads the book for the backfill. Every query filters on {@code connection} — without it a
 * backfill for one connection would pull another's positions in and invent a curve for an
 * account that holds nothing but cash.
 *
 * <p>Reads only. {@code executor_position_leg} is deliberately not touched: its quantities
 * carry the invariant "shares held, never shares intended", and the ENTER order in
 * {@code decision_log} answers the tranche question without that conflict.
 *
 * <p>{@code decision_log} itself has no {@code connection} column, so a plain {@code symbol}
 * join is not a safe key: two connections holding the same symbol would cross-contaminate,
 * and a lower-bound-only time window can silently bind a later order when a position's own
 * ENTER row is missing. The ENTER lateral instead binds on {@code source_signal_id}
 * (populated for every position; each maps to exactly one ENTER row), and both the
 * {@code qty_sync_date} AND {@code fill_date} subqueries bind on
 * {@code order_json->>'position_id'} — all exact identity keys, the established pattern
 * elsewhere in this codebase ({@code DepotHistoryService}, {@code OutcomeBatchJob}). Do not
 * "simplify" this back to a symbol join.
 */
@Repository
public class BackfillSourceRepository {

    private final JdbcClient jdbc;

    public BackfillSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BookPosition> bookPositions(String connection) {
        return jdbc.sql("""
                SELECT p.id,
                       p.symbol,
                       p.status,
                       p.qty,
                       p.entry_price,
                       (p.entry_date AT TIME ZONE 'UTC')::date        AS entry_date,
                       p.exit_price,
                       (p.closed_at  AT TIME ZONE 'UTC')::date        AS closed_at,
                       (e.order_json->>'qty')::numeric                AS enter_qty,
                       (SELECT (MIN(q.ts_decision) AT TIME ZONE 'UTC')::date
                          FROM decision_log q
                         WHERE q.action = 'SYNC'
                           -- LEG_QTY_SYNC is deliberately out of scope: leg-level convergence
                           -- does not move the position quantity for our purposes.
                           AND q.reason_code = 'QTY_SYNC'
                           AND q.order_json->>'position_id' = p.id::text
                           AND q.ts_decision > p.entry_date
                           AND (p.closed_at IS NULL OR q.ts_decision < p.closed_at)) AS qty_sync_date,
                       (SELECT (MIN(f.ts_decision) AT TIME ZONE 'UTC')::date
                          FROM decision_log f
                         WHERE f.action = 'SYNC'
                           -- Deliberately NO reason_code filter, unlike qty_sync_date above: a
                           -- SYNC row of ANY reason code proves the broker held the position
                           -- that day (design spec §2). PAYO's only SYNC is LEG_SEEDED, not
                           -- QTY_SYNC -- a fix that filtered on QTY_SYNC would have missed
                           -- exactly the position that caused this defect.
                           AND f.order_json->>'position_id' = p.id::text
                           AND f.ts_decision > p.entry_date) AS fill_date
                  FROM executor_position p
                  LEFT JOIN LATERAL (
                       SELECT d.order_json
                         FROM decision_log d
                        WHERE d.action = 'ENTER'
                          AND d.signal_id = p.source_signal_id
                        ORDER BY d.ts_decision ASC
                        LIMIT 1
                  ) e ON true
                 WHERE p.connection = :connection
                 ORDER BY p.entry_date ASC""")
                .param("connection", connection)
                .query(this::mapRow)
                .list();
    }

    private BookPosition mapRow(ResultSet rs, int rowNum) throws SQLException {
        Date closed = rs.getDate("closed_at");
        Date qtySync = rs.getDate("qty_sync_date");
        Date fill = rs.getDate("fill_date");
        BigDecimal enterQty = rs.getBigDecimal("enter_qty");
        BigDecimal qty = rs.getBigDecimal("qty");
        return new BookPosition(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("status"),
                qty,
                rs.getBigDecimal("entry_price"),
                rs.getDate("entry_date").toLocalDate(),
                rs.getBigDecimal("exit_price"),
                closed == null ? null : closed.toLocalDate(),
                // null enterQty means no ENTER row was found (e.g. source_signal_id is NULL);
                // PositionLedger.build fails loudly on it rather than guessing a tranche split.
                enterQty,
                qtySync == null ? null : qtySync.toLocalDate(),
                fill == null ? null : fill.toLocalDate());
    }
}
