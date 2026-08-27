package de.visterion.dracul.depot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code depot_equity_snapshot} (V47). JdbcClient-based, mirroring
 * {@link de.visterion.dracul.position.PositionContextRepository}.
 */
@Repository
public class DepotEquitySnapshotRepository {

    /**
     * Outcome of one {@link #upsert}. {@code inserted} comes from {@code (xmax = 0)}: Postgres's
     * affected-row count no longer distinguishes INSERT from UPDATE once the conflict path can
     * also touch a row -- the same reason {@code SpinCandidateRepository.upsertRegistered} uses
     * it. Without it a first write and a correction are indistinguishable, and the "corrected"
     * log line would fire on every new connection and every new trading day.
     */
    public record SnapshotWrite(long id, boolean inserted) {
    }

    private final JdbcClient jdbc;

    public DepotEquitySnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent write. Returns empty when the row already existed with identical values --
     * that is what the {@code WHERE ... IS DISTINCT FROM} buys, and it is why the result must be
     * read with {@code .optional()} and never {@code .single()}: an excluded row makes
     * {@code RETURNING} produce nothing, and {@code .single()} would throw
     * {@code EmptyResultDataAccessException} in production (see
     * {@code SpinCandidateRepository:77-87} for the same trap from the other direction).
     *
     * <p>A re-run with different values CORRECTS the row. Without that path a once-wrong
     * measurement would stay in the chart forever.
     *
     * <p>{@code external_flow} is deliberately absent from the SET clause: a manually entered
     * cash flow must survive every later write.
     */
    public Optional<SnapshotWrite> upsert(String connection, Instant asOf, String granularity,
                                          BigDecimal equity, BigDecimal cash, String currency) {
        BigDecimal positionsValue = equity.subtract(cash);
        return jdbc.sql("""
                INSERT INTO depot_equity_snapshot
                       (connection, as_of, granularity, equity, cash, positions_value, currency)
                VALUES (:connection, :asOf, :granularity, :equity, :cash, :positionsValue, :currency)
                ON CONFLICT (connection, granularity, as_of) DO UPDATE
                   SET equity = EXCLUDED.equity,
                       cash = EXCLUDED.cash,
                       positions_value = EXCLUDED.positions_value,
                       currency = EXCLUDED.currency
                 WHERE (depot_equity_snapshot.equity, depot_equity_snapshot.cash,
                        depot_equity_snapshot.positions_value, depot_equity_snapshot.currency)
                       IS DISTINCT FROM
                       (EXCLUDED.equity, EXCLUDED.cash,
                        EXCLUDED.positions_value, EXCLUDED.currency)
                RETURNING id, (xmax = 0) AS inserted""")
                .param("connection", connection)
                .param("asOf", Timestamp.from(asOf))
                .param("granularity", granularity)
                .param("equity", equity)
                .param("cash", cash)
                .param("positionsValue", positionsValue)
                .param("currency", currency)
                .query(SnapshotWrite.class)
                .optional();
    }

    /** Rows at or after {@code from}, ascending. The boundary is inclusive. */
    public List<DepotEquitySnapshot> series(String connection, String granularity, Instant from) {
        return jdbc.sql("""
                SELECT id, connection, as_of, granularity, equity, cash, positions_value,
                       currency, external_flow, source
                  FROM depot_equity_snapshot
                 WHERE connection = :connection
                   AND granularity = :granularity
                   AND as_of >= :from
                 ORDER BY as_of ASC""")
                .param("connection", connection)
                .param("granularity", granularity)
                .param("from", Timestamp.from(from))
                .query(this::mapRow)
                .list();
    }

    private DepotEquitySnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DepotEquitySnapshot(
                rs.getLong("id"),
                rs.getString("connection"),
                rs.getTimestamp("as_of").toInstant(),
                rs.getString("granularity"),
                rs.getBigDecimal("equity"),
                rs.getBigDecimal("cash"),
                rs.getBigDecimal("positions_value"),
                rs.getString("currency"),
                rs.getBigDecimal("external_flow"),
                rs.getString("source"));
    }
}
