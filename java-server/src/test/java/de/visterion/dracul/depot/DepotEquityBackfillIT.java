package de.visterion.dracul.depot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The backfill against a real Postgres, with the book seeded through real INSERTs. Proves
 * what the unit tests cannot: that the SQL in {@code BackfillSourceRepository} reads the
 * ENTER order (bound on {@code decision_log.signal_id = executor_position.source_signal_id})
 * and the QTY_SYNC date out of the real schema, and that a second run writes nothing.
 */
class DepotEquityBackfillIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);

    private static JdbcClient jdbc;
    private static final ObjectMapper M = new ObjectMapper();

    private static final Instant ANCHOR = Instant.parse("2026-03-05T00:00:00Z");

    private DepotEquitySnapshotRepository snapshots;
    private DepotEquityBackfillService service;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var ds = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = JdbcClient.create(ds);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM depot_equity_snapshot").update();
        jdbc.sql("DELETE FROM executor_position_leg").update();
        jdbc.sql("DELETE FROM executor_position").update();
        jdbc.sql("DELETE FROM decision_log").update();

        // source_signal_id / signal_id is the exact-identity key BackfillSourceRepository
        // binds the ENTER row on (not a symbol join, see that class's javadoc) — both sides
        // must carry the same value or enter_qty comes back NULL and the service throws
        // BackfillConflictException.
        jdbc.sql("""
                INSERT INTO executor_position
                       (id, connection, symbol, side, qty, entry_price, initial_stop,
                        active_stop, entry_date, status, source_signal_id)
                VALUES (900, 'c1', 'AAA', 'BUY', 10, 20.00, 15.00, 15.00,
                        timestamptz '2026-03-03 14:00:00+00', 'OPEN', 'sig-900')""").update();
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, symbol,
                        signal_id, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-03 14:00:00+00', 'v1', 'test',
                        'ENTER', 'AAA', 'sig-900', '{"qty": 10, "limit_price": 20.00}'::jsonb)""").update();

        snapshots = new DepotEquitySnapshotRepository(jdbc);
        snapshots.upsert("c1", ANCHOR, "DAILY",
                new BigDecimal("500.00"), new BigDecimal("400.00"), "EUR");

        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> bars(
                inv.getArgument(1, JsonNode.class).path("symbol").asString()));

        var depotClient = mock(AgoraDepotClient.class);
        when(depotClient.positions("c1")).thenReturn(new PositionsSnapshot(
                List.of(new DepotPosition("AAA", null, new BigDecimal("10"), null, null, null,
                        "USD", null, null)), null));

        service = new DepotEquityBackfillService(snapshots, new BackfillSourceRepository(jdbc),
                agora, depotClient);
    }

    private static JsonNode bars(String symbol) {
        String close = "EURUSD=X".equals(symbol) ? "2.0" : "20.00";
        var root = M.createObjectNode();
        var arr = root.putArray("bars");
        for (String d : List.of("2026-03-03", "2026-03-04", "2026-03-05")) {
            var b = arr.addObject();
            b.put("date", d);
            b.put("close", close);
        }
        return root;
    }

    @Test
    void writesReconstructedRowsAndLandsOnTheAnchor() {
        var report = service.run("c1");

        // The left edge is the first bar day at-or-after firstEntry (2026-03-03): AAA's bars
        // start exactly there, so there is no earlier bar day to open the window one day
        // early on. The window is [03-03, 03-04] -- two reconstructed days, not the anchor.
        assertThat(report.daysWritten()).isEqualTo(2);
        assertThat(report.seamDelta()).isEqualByComparingTo("0.00");
        assertThat(sourceAt("2026-03-04")).isEqualTo("RECONSTRUCTED");
        assertThat(sourceAt("2026-03-05")).isEqualTo("MEASURED");
    }

    @Test
    void aSecondRunWritesNothing() {
        service.run("c1");

        var second = service.run("c1");

        assertThat(second.daysWritten()).isZero();
        assertThat(second.daysUnchanged()).isEqualTo(2);
    }

    @Test
    void reportCarriesTheWindowAndTheSeam() {
        var report = service.run("c1");

        assertThat(report.from()).isEqualTo("2026-03-03");
        assertThat(report.to()).isEqualTo("2026-03-04");
        assertThat(report.seamDeltaPct()).isNotNull();
    }

    private String sourceAt(String day) {
        return jdbc.sql("""
                SELECT source FROM depot_equity_snapshot
                 WHERE connection = 'c1' AND granularity = 'DAILY' AND as_of = :a""")
                .param("a", Timestamp.from(Instant.parse(day + "T00:00:00Z")))
                .query(String.class)
                .single();
    }
}
