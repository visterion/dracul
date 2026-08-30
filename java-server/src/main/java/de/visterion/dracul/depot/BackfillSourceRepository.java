package de.visterion.dracul.depot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
                         WHERE q.symbol = p.symbol
                           AND q.action = 'SYNC'
                           AND q.reason_code = 'QTY_SYNC'
                           AND q.ts_decision > p.entry_date)          AS qty_sync_date
                  FROM executor_position p
                  LEFT JOIN LATERAL (
                       SELECT d.order_json
                         FROM decision_log d
                        WHERE d.symbol = p.symbol
                          AND d.action = 'ENTER'
                          AND d.ts_decision >= p.entry_date - interval '1 hour'
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
        java.sql.Date closed = rs.getDate("closed_at");
        java.sql.Date qtySync = rs.getDate("qty_sync_date");
        java.math.BigDecimal enterQty = rs.getBigDecimal("enter_qty");
        java.math.BigDecimal qty = rs.getBigDecimal("qty");
        return new BookPosition(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("status"),
                qty,
                rs.getBigDecimal("entry_price"),
                rs.getDate("entry_date").toLocalDate(),
                rs.getBigDecimal("exit_price"),
                closed == null ? null : closed.toLocalDate(),
                // null enterQty means no ENTER row was found; the service turns that into a
                // 409 rather than guessing a tranche split.
                enterQty,
                qtySync == null ? null : qtySync.toLocalDate());
    }
}
