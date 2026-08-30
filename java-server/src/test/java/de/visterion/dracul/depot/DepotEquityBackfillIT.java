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
 * and the QTY_SYNC date (bound on {@code order_json->>'position_id'}, with the entry/close
 * time window) out of the real schema; that {@code connection} actually filters the book
 * rather than cross-contaminating another connection's positions; and that a second run
 * writes nothing.
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

        // Position 900 (connection c1, symbol AAA) is a deliberate TWO-tranche position: the
        // ENTER order is for 5 shares (enter_qty), the book holds 10 (qty), and a QTY_SYNC row
        // dated 2026-03-04 accounts for the other 5. This is what proves the QTY_SYNC subquery
        // reads its date out of the real schema rather than the code merely compiling against
        // an untested query — see qtySyncDateSplitsTheHoldingAcrossTranches below.
        //
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
                        'ENTER', 'AAA', 'sig-900', '{"qty": 5, "limit_price": 20.00}'::jsonb)""").update();
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, reason_code,
                        symbol, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-04 10:00:00+00', 'v1', 'test',
                        'SYNC', 'QTY_SYNC', 'AAA', '{"position_id": "900"}'::jsonb)""").update();
        // Decoy: a QTY_SYNC row for the SAME position, dated BEFORE its entry_date. It exists
        // only to make the subquery's time bound ("q.ts_decision > p.entry_date") load-bearing:
        // without that bound this earlier row -- not the real 03-04 one -- would win the
        // MIN(ts_decision) aggregation, dating tranche 2 to before the position even opened.
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, reason_code,
                        symbol, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-01 09:00:00+00', 'v1', 'test',
                        'SYNC', 'QTY_SYNC', 'AAA', '{"position_id": "900"}'::jsonb)""").update();

        // Decoy: a QTY_SYNC row inside position 900's time window (after its entry_date, before
        // the real 03-04 sync) that belongs to a DIFFERENT position. It exists only to make the
        // subquery's exact-identity binding ("q.order_json->>'position_id' = p.id::text")
        // load-bearing: without it this 03-03 row would win the MIN(ts_decision), dating
        // tranche 2 a day early and flipping the cash assertions in
        // qtySyncDateSplitsTheHoldingAcrossTranches. There is no foreign key on position_id,
        // so no executor_position row is needed for 999.
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, reason_code,
                        symbol, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-03 18:00:00+00', 'v1', 'test',
                        'SYNC', 'QTY_SYNC', 'AAA', '{"position_id": "999"}'::jsonb)""").update();

        // Position 901 belongs to a DIFFERENT connection (c2), a different symbol (BBB), and
        // its own signal. Nothing below runs the backfill for c2 -- its only job is to prove
        // bookPositions' "WHERE p.connection = :connection" actually filters: if it didn't,
        // BBB would leak into c1's book, fail the broker-holdings check (the depotClient stub
        // below only knows about c1's AAA), and land in excludedPositions.
        jdbc.sql("""
                INSERT INTO executor_position
                       (id, connection, symbol, side, qty, entry_price, initial_stop,
                        active_stop, entry_date, status, source_signal_id)
                VALUES (901, 'c2', 'BBB', 'BUY', 5, 10.00, 8.00, 8.00,
                        timestamptz '2026-03-03 14:00:00+00', 'OPEN', 'sig-901')""").update();
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, symbol,
                        signal_id, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-03 14:00:00+00', 'v1', 'test',
                        'ENTER', 'BBB', 'sig-901', '{"qty": 5, "limit_price": 10.00}'::jsonb)""").update();

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
        assertThat(report.daysInserted()).isEqualTo(2);
        assertThat(report.daysCorrected()).isZero();
        // Pins the anchor rule where it is actually named: a fresh run must not "unchange"
        // anything, only write. (aSecondRunWritesNothing is the idempotency case; this one is
        // the first-run case, and the two must not be conflated -- a mutation that makes the
        // anchor day silently count as unchanged on a FIRST run would slip past
        // aSecondRunWritesNothing's own assertions unnoticed otherwise.)
        assertThat(report.daysUnchanged()).isZero();
        assertThat(report.seamDelta()).isEqualByComparingTo("0.00");
        assertThat(sourceAt("2026-03-04")).isEqualTo("RECONSTRUCTED");
        assertThat(sourceAt("2026-03-05")).isEqualTo("MEASURED");
    }

    @Test
    void aSecondRunWritesNothing() {
        service.run("c1");

        var second = service.run("c1");

        assertThat(second.daysInserted()).isZero();
        assertThat(second.daysCorrected()).isZero();
        assertThat(second.daysUnchanged()).isEqualTo(2);
        assertThat(second.daysDeletedStale()).isZero();
    }

    @Test
    void reportCarriesTheWindowAndTheSeam() {
        var report = service.run("c1");

        assertThat(report.from()).isEqualTo("2026-03-03");
        assertThat(report.to()).isEqualTo("2026-03-04");
        assertThat(report.seamDeltaPct()).isNotNull();
    }

    /**
     * Proves the QTY_SYNC subquery reads a real date out of {@code decision_log} -- bound on
     * both {@code order_json->>'position_id'} AND the entry/close time window -- rather than
     * always returning NULL (which would silently fall back to {@code entryDate.plusDays(1)},
     * see {@code PositionLedger.build}) or picking up the decoy row seeded above.
     *
     * <p>{@code equity} alone cannot tell tranche 1 from tranche 2 here: buying more of the
     * same symbol at the same price that is already priced into the curve does not change
     * equity, only its cash/positions split (500.00 on both days, confirmed by this test).
     * {@code cash} is the signal that actually moves: 450.00 while only tranche 1 (5 shares)
     * is held, dropping to 400.00 -- the anchor's own cash -- once tranche 2's 5 shares are
     * bought on the QTY_SYNC date. A wrong or ignored QTY_SYNC date (NULL, the decoy's
     * 2026-03-01, or entryDate + 1 -- which happens to coincide with the real date here, so
     * this alone would not catch every regression) would move that transition to the wrong
     * day and flip one of these two cash values.
     */
    @Test
    void qtySyncDateSplitsTheHoldingAcrossTranches() {
        service.run("c1");

        assertThat(cashAt("2026-03-03")).isEqualByComparingTo("450.00");
        assertThat(cashAt("2026-03-04")).isEqualByComparingTo("400.00");
        assertThat(equityAt("2026-03-03")).isEqualByComparingTo("500.00");
        assertThat(equityAt("2026-03-04")).isEqualByComparingTo("500.00");
    }

    /**
     * Proves {@code bookPositions}' {@code WHERE p.connection = :connection} is load-bearing.
     * Position 901 (connection c2, symbol BBB) exists in the same table; if the filter were
     * ever dropped it would leak into c1's book, fail the broker-holdings check (the mocked
     * depotClient only knows AAA), and show up here.
     */
    @Test
    void aDifferentConnectionsPositionsDoNotLeakIn() {
        var report = service.run("c1");

        assertThat(report.excludedPositions()).isEmpty();
    }

    /**
     * A re-run after the book improves usually NARROWS the window — a position turns out never
     * to have been filled, or drops out of the broker's holdings, and the first holding date
     * moves right. The rows an earlier, wider run wrote before that date are computed from
     * numbers this run considers wrong; left behind they are drawn dashed and connected as if
     * they belonged to this reconstruction.
     *
     * <p>The measured rows in the same table must survive untouched, which is what the anchor
     * (2026-03-05) and the later measured row (2026-03-06) assert here; the source and anchor
     * bounds themselves are pinned directly in
     * {@code DepotEquitySnapshotRepositoryIT.deleteStaleReconstructedNeverTouchesAMeasuredRow}.
     */
    @Test
    void aReconstructedRowOutsideTheCurrentWindowIsDeletedWhileMeasuredRowsSurvive() {
        Instant stale = Instant.parse("2026-03-02T00:00:00Z");
        Instant laterMeasured = Instant.parse("2026-03-06T00:00:00Z");
        snapshots.upsertReconstructed("c1", stale, "DAILY",
                new BigDecimal("111.00"), new BigDecimal("111.00"), "EUR");
        snapshots.upsert("c1", laterMeasured, "DAILY",
                new BigDecimal("510.00"), new BigDecimal("410.00"), "EUR");

        var report = service.run("c1");

        assertThat(report.daysDeletedStale()).isEqualTo(1);
        assertThat(rowsAt("2026-03-02")).isZero();
        assertThat(sourceAt("2026-03-05")).isEqualTo("MEASURED");
        assertThat(sourceAt("2026-03-06")).isEqualTo("MEASURED");
        assertThat(sourceAt("2026-03-03")).isEqualTo("RECONSTRUCTED");
        assertThat(sourceAt("2026-03-04")).isEqualTo("RECONSTRUCTED");
    }

    /**
     * Both early-return paths must still clear an earlier, wider run's stale RECONSTRUCTED
     * rows before returning a zero report: an empty book means there is no holding left to
     * reconstruct a curve from, but the rows a previous, wider run wrote are exactly as stale
     * as if the window had merely shrunk, and must not survive untouched.
     */
    @Test
    void anEmptyBookStillDeletesStaleReconstructedRowsBeforeReturning() {
        // A fresh connection with a MEASURED anchor and NO executor_position rows at all --
        // bookPositions("c3") returns an empty list, taking the book.isEmpty() early return.
        Instant anchor = Instant.parse("2026-03-05T00:00:00Z");
        snapshots.upsert("c3", anchor, "DAILY",
                new BigDecimal("500.00"), new BigDecimal("400.00"), "EUR");
        Instant stale1 = Instant.parse("2026-03-02T00:00:00Z");
        Instant stale2 = Instant.parse("2026-03-03T00:00:00Z");
        snapshots.upsertReconstructed("c3", stale1, "DAILY",
                new BigDecimal("111.00"), new BigDecimal("111.00"), "EUR");
        snapshots.upsertReconstructed("c3", stale2, "DAILY",
                new BigDecimal("122.00"), new BigDecimal("122.00"), "EUR");

        var report = service.run("c3");

        assertThat(report.daysDeletedStale()).isEqualTo(2);
        assertThat(rowsAt("2026-03-02", "c3")).isZero();
        assertThat(rowsAt("2026-03-03", "c3")).isZero();
        assertThat(sourceAt("2026-03-05", "c3")).isEqualTo("MEASURED");
    }

    /**
     * Same guard as above, but the book is non-empty and every position is excluded by the
     * ledger instead: an OPEN position the broker never received. That takes the
     * firstHoldingDate.isEmpty() early return, not the book.isEmpty() one -- the two paths are
     * reached through different conditions and each needed its own hoisted delete call.
     */
    @Test
    void aFullyExcludedBookStillDeletesStaleReconstructedRowsBeforeReturning() {
        jdbc.sql("""
                INSERT INTO executor_position
                       (id, connection, symbol, side, qty, entry_price, initial_stop,
                        active_stop, entry_date, status, source_signal_id)
                VALUES (902, 'c4', 'CCC', 'BUY', 5, 10.00, 8.00, 8.00,
                        timestamptz '2026-03-03 14:00:00+00', 'OPEN', 'sig-902')""").update();
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, symbol,
                        signal_id, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-03 14:00:00+00', 'v1', 'test',
                        'ENTER', 'CCC', 'sig-902', '{"qty": 5, "limit_price": 10.00}'::jsonb)""").update();
        // depotClient is not stubbed for "c4" -> AgoraDepotClient mock returns null unless
        // stubbed, so positions("c4") must be stubbed explicitly. The broker answer carries one
        // decoy holding for a symbol NOT in c4's book ("ZZZ") rather than an empty list: this
        // is a genuine, non-degraded broker answer that simply does not mention CCC, which is
        // exactly the "open in the book, absent at the broker" exclusion PositionLedger.build
        // applies -- see that reasoning there. An EMPTY broker answer here would instead trip
        // the new zero-broker-holdings guard in DepotEquityBackfillService (a book with an OPEN
        // position pairing with zero total broker holdings, tested separately below), which
        // is a different situation from this one: a real, non-empty broker report that simply
        // does not carry this one symbol.
        var depotClient = mock(AgoraDepotClient.class);
        when(depotClient.positions("c4")).thenReturn(new PositionsSnapshot(
                List.of(new DepotPosition("ZZZ", null, new BigDecimal("1"), null, null, null,
                        "USD", null, null)), null));
        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> bars(
                inv.getArgument(1, JsonNode.class).path("symbol").asString()));
        var excludedService = new DepotEquityBackfillService(snapshots,
                new BackfillSourceRepository(jdbc), agora, depotClient);

        Instant anchor = Instant.parse("2026-03-05T00:00:00Z");
        snapshots.upsert("c4", anchor, "DAILY",
                new BigDecimal("500.00"), new BigDecimal("400.00"), "EUR");
        Instant stale = Instant.parse("2026-03-02T00:00:00Z");
        snapshots.upsertReconstructed("c4", stale, "DAILY",
                new BigDecimal("111.00"), new BigDecimal("111.00"), "EUR");

        var report = excludedService.run("c4");

        assertThat(report.daysDeletedStale()).isEqualTo(1);
        assertThat(rowsAt("2026-03-02", "c4")).isZero();
        assertThat(sourceAt("2026-03-05", "c4")).isEqualTo("MEASURED");
        assertThat(report.excludedPositions()).isNotEmpty();
    }

    /**
     * The guard's whole reason to exist: a degraded broker answer (an HTTP 200 with an
     * unexpected shape, see {@code AgoraDepotClient.positions()}) silently degrades into ZERO
     * holdings, indistinguishable from a real empty account. With the stale-delete hoisted
     * above both early returns (commit 874b2889), an unguarded run here would read the empty
     * broker answer as "every OPEN position is absent at the broker", empty the ledger, take
     * the {@code firstHoldingDate.isEmpty()} path, and irrecoverably delete every seeded
     * RECONSTRUCTED row below. Position 900 is OPEN in the book (seeded in {@code seed()}); the
     * depotClient here answers with zero holdings for c1 instead of the AAA holding the default
     * stub provides.
     *
     * <p>The surviving-rows assertion is the point of this test, not the thrown exception
     * alone -- an exception thrown AFTER the delete already ran would pass a naive
     * assertThrows-only test while still having wiped the curve.
     */
    @Test
    void aDegradedZeroBrokerAnswerIsRefusedRatherThanReadAsAnEmptyAccount() {
        Instant stale = Instant.parse("2026-03-02T00:00:00Z");
        snapshots.upsertReconstructed("c1", stale, "DAILY",
                new BigDecimal("111.00"), new BigDecimal("111.00"), "EUR");

        var degradedDepotClient = mock(AgoraDepotClient.class);
        when(degradedDepotClient.positions("c1")).thenReturn(new PositionsSnapshot(List.of(), null));
        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> bars(
                inv.getArgument(1, JsonNode.class).path("symbol").asString()));
        var guardedService = new DepotEquityBackfillService(snapshots,
                new BackfillSourceRepository(jdbc), agora, degradedDepotClient);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guardedService.run("c1"))
                .isInstanceOf(DepotEquityBackfillService.BackfillConflictException.class);

        assertThat(rowsAt("2026-03-02")).isEqualTo(1);
        assertThat(sourceAt("2026-03-02")).isEqualTo("RECONSTRUCTED");
    }

    /**
     * The guard must not be broader than the degraded-answer case it exists for: a book of
     * only CLOSED positions legitimately pairs with zero broker holdings -- a normal,
     * fully-liquidated account -- and must still reach the existing fully-excluded behaviour
     * (stale rows deleted, {@code daysDeletedStale} reported, the MEASURED anchor untouched).
     * Position 903 here is CLOSED, not OPEN, so {@code book.stream().anyMatch(OPEN)} is false
     * and the guard must not fire.
     */
    @Test
    void aClosedOutBookWithNoBrokerHoldingsIsNotBlockedByTheGuard() {
        jdbc.sql("""
                INSERT INTO executor_position
                       (id, connection, symbol, side, qty, entry_price, initial_stop,
                        active_stop, entry_date, status, source_signal_id, exit_price, closed_at)
                VALUES (903, 'c5', 'DDD', 'BUY', 5, 10.00, 8.00, 8.00,
                        timestamptz '2026-03-03 14:00:00+00', 'CLOSED', 'sig-903',
                        12.00, timestamptz '2026-03-04 14:00:00+00')""").update();
        jdbc.sql("""
                INSERT INTO decision_log
                       (log_id, ts_decision, rule_version, trigger_type, action, symbol,
                        signal_id, order_json)
                VALUES (gen_random_uuid(), timestamptz '2026-03-03 14:00:00+00', 'v1', 'test',
                        'ENTER', 'DDD', 'sig-903', '{"qty": 5, "limit_price": 10.00}'::jsonb)""").update();

        var depotClient = mock(AgoraDepotClient.class);
        when(depotClient.positions("c5")).thenReturn(new PositionsSnapshot(List.of(), null));
        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> bars(
                inv.getArgument(1, JsonNode.class).path("symbol").asString()));
        var closedOutService = new DepotEquityBackfillService(snapshots,
                new BackfillSourceRepository(jdbc), agora, depotClient);

        Instant anchor = Instant.parse("2026-03-05T00:00:00Z");
        snapshots.upsert("c5", anchor, "DAILY",
                new BigDecimal("500.00"), new BigDecimal("400.00"), "EUR");
        Instant stale = Instant.parse("2026-03-02T00:00:00Z");
        snapshots.upsertReconstructed("c5", stale, "DAILY",
                new BigDecimal("111.00"), new BigDecimal("111.00"), "EUR");

        var report = closedOutService.run("c5");

        assertThat(report.daysDeletedStale()).isEqualTo(1);
        assertThat(rowsAt("2026-03-02", "c5")).isZero();
        assertThat(sourceAt("2026-03-05", "c5")).isEqualTo("MEASURED");
    }

    private int rowsAt(String day) {
        return rowsAt(day, "c1");
    }

    private int rowsAt(String day, String connection) {
        return jdbc.sql("""
                SELECT count(*) FROM depot_equity_snapshot
                 WHERE connection = :c AND granularity = 'DAILY' AND as_of = :a""")
                .param("c", connection)
                .param("a", Timestamp.from(Instant.parse(day + "T00:00:00Z")))
                .query(Integer.class)
                .single();
    }

    private String sourceAt(String day) {
        return sourceAt(day, "c1");
    }

    private String sourceAt(String day, String connection) {
        return jdbc.sql("""
                SELECT source FROM depot_equity_snapshot
                 WHERE connection = :c AND granularity = 'DAILY' AND as_of = :a""")
                .param("c", connection)
                .param("a", Timestamp.from(Instant.parse(day + "T00:00:00Z")))
                .query(String.class)
                .single();
    }

    private BigDecimal equityAt(String day) {
        return jdbc.sql("""
                SELECT equity FROM depot_equity_snapshot
                 WHERE connection = 'c1' AND granularity = 'DAILY' AND as_of = :a""")
                .param("a", Timestamp.from(Instant.parse(day + "T00:00:00Z")))
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal cashAt(String day) {
        return jdbc.sql("""
                SELECT cash FROM depot_equity_snapshot
                 WHERE connection = 'c1' AND granularity = 'DAILY' AND as_of = :a""")
                .param("a", Timestamp.from(Instant.parse(day + "T00:00:00Z")))
                .query(BigDecimal.class)
                .single();
    }
}
