package de.visterion.dracul.executor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the REAL V48 migration (not a copy of its SQL) against a real Postgres: migrate to V47,
 * seed synthetic positions and decision_log rows, migrate to latest, then assert the two columns
 * exist and are nullable and that both backfills picked exactly the rows the spec says they
 * should — {@code entry_filled_at} from a SYNC row of any reason code, {@code broker_stop} from
 * the highest broker-confirmed stop price and never below {@code active_stop}.
 *
 * <p>All ids, symbols and prices below are synthetic — this is a public repo and no production row
 * may be reproduced here. The shape of an OPEN position whose ONLY SYNC row is a LEG_SEEDED is
 * reproduced structurally, with an invented id and symbol.
 */
class V48BrokerStopMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);

    /** OPEN, two SYNC rows — the earliest ts_decision must win. */
    private static final long OPEN_TWO_SYNCS = 9001L;
    /** OPEN, only a LEG_SEEDED SYNC row — a reason-code-filtered backfill would miss it. */
    private static final long OPEN_LEG_SEEDED_ONLY = 9002L;
    /** OPEN, no SYNC row at all — must stay NULL. */
    private static final long OPEN_NO_SYNC = 9003L;
    /** CLOSED, with a SYNC row — must stay NULL (the backfill is OPEN-only). */
    private static final long CLOSED_WITH_SYNC = 9004L;
    /** OPEN, one confirmed MODIFY_STOP above active_stop — broker_stop takes that price. */
    private static final long OPEN_MODIFY_STOP = 9005L;
    /**
     * OPEN, a MODIFY_STOP and two PARTIAL_TRANCHE_RATCHET rows, the later partial LOWER than the
     * earlier one — the HIGHEST confirmed price wins over both the modify and the last partial.
     */
    private static final long OPEN_PARTIAL_RATCHET = 9006L;
    /** OPEN, no confirmed stop price at all — broker_stop falls back to active_stop. */
    private static final long OPEN_NO_CONFIRMED_STOP = 9007L;
    /** CLOSED, with a confirmed MODIFY_STOP — broker_stop must stay NULL (no live leg). */
    private static final long CLOSED_WITH_MODIFY_STOP = 9008L;

    /** Every synthetic position is entered here, before any seeded decision row. */
    private static final Instant ENTRY = Instant.parse("2026-07-20T10:00:00Z");
    private static final Instant EARLY = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-05T10:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-08-07T10:00:00Z");

    /** The active_stop every synthetic position carries. */
    private static final BigDecimal ACTIVE_STOP = new BigDecimal("95.000000");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void addsBothNullableColumnsAndBackfillsEntryFilledAtForOpenRowsWithAnySyncRow() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("47"))
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            insertPosition(conn, OPEN_TWO_SYNCS, "SYNTHA", "OPEN");
            insertPosition(conn, OPEN_LEG_SEEDED_ONLY, "SYNTHB", "OPEN");
            insertPosition(conn, OPEN_NO_SYNC, "SYNTHC", "OPEN");
            insertPosition(conn, CLOSED_WITH_SYNC, "SYNTHD", "CLOSED");
            insertPosition(conn, OPEN_MODIFY_STOP, "SYNTHE", "OPEN");
            insertPosition(conn, OPEN_PARTIAL_RATCHET, "SYNTHF", "OPEN");
            insertPosition(conn, OPEN_NO_CONFIRMED_STOP, "SYNTHG", "OPEN");
            insertPosition(conn, CLOSED_WITH_MODIFY_STOP, "SYNTHH", "CLOSED");

            // Two SYNC rows for the same position: MIN(ts_decision) must win.
            insertSync(conn, OPEN_TWO_SYNCS, "SYNTHA", "ENTRY_PRICE_SYNC", LATE);
            insertSync(conn, OPEN_TWO_SYNCS, "SYNTHA", "QTY_SYNC", EARLY);
            // A position whose only evidence of a broker holding is a seeded leg.
            insertSync(conn, OPEN_LEG_SEEDED_ONLY, "SYNTHB", "LEG_SEEDED", EARLY);
            insertSync(conn, CLOSED_WITH_SYNC, "SYNTHD", "ENTRY_PRICE_SYNC", EARLY);
            // A non-SYNC row for the position with no SYNC row: must not be picked up.
            insertModifyStop(conn, "SYNTHC", EARLY, null);

            // broker_stop fixture. MODIFY_STOP rows carry no position_id and are attributed by
            // symbol; PARTIAL_TRANCHE_RATCHET rows carry their own position_id.
            insertModifyStop(conn, "SYNTHE", EARLY, "96.50");
            insertModifyStop(conn, "SYNTHF", EARLY, "96.50");
            insertPartialRatchet(conn, OPEN_PARTIAL_RATCHET, "SYNTHF", LATE, "97.25");
            insertPartialRatchet(conn, OPEN_PARTIAL_RATCHET, "SYNTHF", LATEST, "97.10");
            insertModifyStop(conn, "SYNTHH", EARLY, "96.50");

            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertThat(isNullable(conn, "broker_stop")).isTrue();
            assertThat(isNullable(conn, "entry_filled_at")).isTrue();
            assertThat(dataType(conn, "broker_stop")).isEqualTo("numeric");
            assertThat(dataType(conn, "entry_filled_at")).isEqualTo("timestamp with time zone");

            assertThat(entryFilledAt(conn, OPEN_TWO_SYNCS)).isEqualTo(Timestamp.from(EARLY));
            assertThat(entryFilledAt(conn, OPEN_LEG_SEEDED_ONLY)).isEqualTo(Timestamp.from(EARLY));
            assertThat(entryFilledAt(conn, OPEN_NO_SYNC)).isNull();
            assertThat(entryFilledAt(conn, CLOSED_WITH_SYNC)).isNull();

            // broker_stop: the highest broker-confirmed price, never below active_stop, OPEN only.
            assertThat(brokerStop(conn, OPEN_MODIFY_STOP))
                    .isEqualByComparingTo(new BigDecimal("96.50"));
            assertThat(brokerStop(conn, OPEN_PARTIAL_RATCHET))
                    .isEqualByComparingTo(new BigDecimal("97.25"));
            assertThat(brokerStop(conn, OPEN_NO_CONFIRMED_STOP)).isEqualByComparingTo(ACTIVE_STOP);
            assertThat(brokerStop(conn, OPEN_TWO_SYNCS)).isEqualByComparingTo(ACTIVE_STOP);
            assertThat(brokerStop(conn, CLOSED_WITH_MODIFY_STOP)).isNull();
            assertThat(brokerStop(conn, CLOSED_WITH_SYNC)).isNull();
        }
    }

    private static void insertPosition(Connection conn, long id, String symbol, String status)
            throws Exception {
        String sql = """
                INSERT INTO executor_position
                    (id, connection, symbol, side, qty, entry_price, initial_stop, active_stop,
                     status, entry_date)
                VALUES (?, 'depot-1', ?, 'BUY', 10, 100.00, 90.00, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, symbol);
            ps.setBigDecimal(3, ACTIVE_STOP);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(ENTRY));
            ps.executeUpdate();
        }
    }

    private static void insertSync(Connection conn, long positionId, String symbol,
            String reasonCode, Instant ts) throws Exception {
        insertDecision(conn, symbol, "SYNC", reasonCode, ts,
                "{\"position_id\": " + positionId + "}", "{}");
    }

    /** A confirmed stop modify: no position_id, the price in {@code inputs_snapshot.new_stop}. */
    private static void insertModifyStop(Connection conn, String symbol, Instant ts, String newStop)
            throws Exception {
        String inputs = newStop == null ? "{}" : "{\"new_stop\": " + newStop + "}";
        insertDecision(conn, symbol, "MODIFY_STOP", null, ts, "{}", inputs);
    }

    /** A partial ratchet: its own position_id, the moved leg's confirmed attempted_stop. */
    private static void insertPartialRatchet(Connection conn, long positionId, String symbol,
            Instant ts, String attemptedStop) throws Exception {
        insertDecision(conn, symbol, "ESCALATE", "PARTIAL_TRANCHE_RATCHET", ts,
                "{\"position_id\": " + positionId + ", \"attempted_stop\": " + attemptedStop + "}",
                "{}");
    }

    private static void insertDecision(Connection conn, String symbol, String action,
            String reasonCode, Instant ts, String orderJson, String inputsSnapshot)
            throws Exception {
        String sql = """
                INSERT INTO decision_log
                    (log_id, ts_decision, rule_version, trigger_type, symbol, action, reason_code,
                     order_json, inputs_snapshot)
                VALUES (gen_random_uuid(), ?, 'exec-test', 'MAINTENANCE', ?, ?, ?, ?::jsonb, ?::jsonb)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(ts));
            ps.setString(2, symbol);
            ps.setString(3, action);
            ps.setString(4, reasonCode);
            ps.setString(5, orderJson);
            ps.setString(6, inputsSnapshot);
            ps.executeUpdate();
        }
    }

    private static Timestamp entryFilledAt(Connection conn, long id) throws Exception {
        return single(conn, "SELECT entry_filled_at FROM executor_position WHERE id = ?", id,
                rs -> rs.getTimestamp(1));
    }

    private static BigDecimal brokerStop(Connection conn, long id) throws Exception {
        return single(conn, "SELECT broker_stop FROM executor_position WHERE id = ?", id,
                rs -> rs.getBigDecimal(1));
    }

    private interface RowReader<T> { T read(ResultSet rs) throws Exception; }

    private static <T> T single(Connection conn, String sql, long id, RowReader<T> reader)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row " + id + " exists").isTrue();
                return reader.read(rs);
            }
        }
    }

    private static boolean isNullable(Connection conn, String column) throws Exception {
        return "YES".equals(columnMeta(conn, column, "is_nullable"));
    }

    private static String dataType(Connection conn, String column) throws Exception {
        return columnMeta(conn, column, "data_type");
    }

    private static String columnMeta(Connection conn, String column, String field) throws Exception {
        String sql = "SELECT " + field + " FROM information_schema.columns "
                + "WHERE table_name = 'executor_position' AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("column " + column + " exists").isTrue();
                return rs.getString(1);
            }
        }
    }
}
