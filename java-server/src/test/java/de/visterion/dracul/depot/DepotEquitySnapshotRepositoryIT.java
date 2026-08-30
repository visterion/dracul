package de.visterion.dracul.depot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constraint and ON CONFLICT semantics of V47. These are pure SQL and only provable against a
 * real Postgres: the three-way outcome of the upsert (fresh insert / unchanged / corrected)
 * rests on {@code (xmax = 0)}, which has no equivalent in an in-memory fake.
 */
class DepotEquitySnapshotRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withPrivilegedMode(true);

    private static JdbcClient jdbc;
    private DepotEquitySnapshotRepository repo;

    private static final Instant DAY = Instant.parse("2026-01-05T00:00:00Z");

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
    void clean() {
        jdbc.sql("DELETE FROM depot_equity_snapshot").update();
        repo = new DepotEquitySnapshotRepository(jdbc);
    }

    @Test
    void freshInsertReportsInserted() {
        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(w).isPresent();
        assertThat(w.get().inserted()).isTrue();
    }

    @Test
    void identicalReplayWritesNothing() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(w).isEmpty();
    }

    @Test
    void changedValuesCorrectTheRowAndReportNotInserted() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("111.00"), new BigDecimal("40.00"), "EUR");

        assertThat(w).isPresent();
        assertThat(w.get().inserted()).isFalse();
        assertThat(repo.series("conn-1", "DAILY", DAY).getFirst().equity())
                .isEqualByComparingTo("111.00");
    }

    @Test
    void updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");
        jdbc.sql("""
                UPDATE depot_equity_snapshot
                   SET external_flow = 250.00, source = 'RECONSTRUCTED'
                 WHERE connection = 'conn-1' AND granularity = 'DAILY' AND as_of = :t""")
                .param("t", java.sql.Timestamp.from(DAY))
                .update();

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("111.00"), new BigDecimal("40.00"), "EUR");

        assertThat(w).isPresent();
        assertThat(w.get().inserted()).isFalse();
        DepotEquitySnapshot row = repo.series("conn-1", "DAILY", DAY).getFirst();
        assertThat(row.equity()).isEqualByComparingTo("111.00");
        assertThat(row.externalFlow()).isEqualByComparingTo("250.00");
        assertThat(row.source()).isEqualTo("MEASURED");
    }

    @Test
    void positionsValueIsEquityMinusCash() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.series("conn-1", "DAILY", DAY).getFirst().positionsValue())
                .isEqualByComparingTo("60.00");
    }

    @Test
    void dailyAndIntradayAtTheSameInstantAreDistinctRows() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");
        repo.upsert("conn-1", DAY, "INTRADAY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.series("conn-1", "DAILY", DAY)).hasSize(1);
        assertThat(repo.series("conn-1", "INTRADAY", DAY)).hasSize(1);
    }

    @Test
    void seriesIsScopedToItsConnection() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");
        repo.upsert("conn-2", DAY, "DAILY", new BigDecimal("200.00"), new BigDecimal("50.00"), "USD");

        assertThat(repo.series("conn-2", "DAILY", DAY)).singleElement()
                .extracting(DepotEquitySnapshot::equity)
                .isEqualTo(new BigDecimal("200.00"));
    }

    @Test
    void seriesIncludesARowExactlyOnTheFromBoundary() {
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.series("conn-1", "DAILY", DAY)).hasSize(1);
    }

    @Test
    void seriesIsAscendingByAsOf() {
        Instant later = DAY.plusSeconds(86_400);
        repo.upsert("conn-1", later, "DAILY", new BigDecimal("120.00"), new BigDecimal("40.00"), "EUR");
        repo.upsert("conn-1", DAY, "DAILY", new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.series("conn-1", "DAILY", DAY))
                .extracting(DepotEquitySnapshot::asOf)
                .containsExactly(DAY, later);
    }

    @Test
    void concurrentWritesToTheSameKeyYieldExactlyOneRow() throws Exception {
        // Two real connections, so the two threads genuinely contend on the unique index.
        // DriverManagerDataSource hands out a fresh connection per request; the shared
        // SingleConnectionDataSource above would serialise them and prove nothing.
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var repoA = new DepotEquitySnapshotRepository(JdbcClient.create(ds));
        var repoB = new DepotEquitySnapshotRepository(JdbcClient.create(ds));

        var start = new java.util.concurrent.CountDownLatch(1);
        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        Runnable write = () -> {
            try {
                start.await();
                repoA.upsert("conn-1", DAY, "DAILY",
                        new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        Runnable write2 = () -> {
            try {
                start.await();
                repoB.upsert("conn-1", DAY, "DAILY",
                        new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");
            } catch (Throwable t) {
                errors.add(t);
            }
        };

        Thread t1 = new Thread(write);
        Thread t2 = new Thread(write2);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        assertThat(errors).isEmpty();
        assertThat(repo.series("conn-1", "DAILY", DAY)).hasSize(1);
    }

    @Test
    void unknownGranularityIsRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO depot_equity_snapshot
                       (connection, as_of, granularity, equity, cash, positions_value, currency)
                VALUES ('conn-1', :t, 'HOURLY', 1, 1, 0, 'EUR')""")
                .param("t", java.sql.Timestamp.from(DAY))
                .update())
                .hasMessageContaining("depot_equity_snapshot_granularity_ck");
    }

    @Test
    void unknownSourceIsRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO depot_equity_snapshot
                       (connection, as_of, granularity, equity, cash, positions_value, currency, source)
                VALUES ('conn-1', :t, 'DAILY', 1, 1, 0, 'EUR', 'GUESSED')""")
                .param("t", java.sql.Timestamp.from(DAY))
                .update())
                .hasMessageContaining("depot_equity_snapshot_source_ck");
    }

    @Test
    void nullCurrencyIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO depot_equity_snapshot
                       (connection, as_of, granularity, equity, cash, positions_value, currency)
                VALUES ('conn-1', :t, 'DAILY', 1, 1, 0, NULL)""")
                .param("t", java.sql.Timestamp.from(DAY))
                .update())
                .hasMessageContaining("currency");
    }

    private String sourceOf(String connection, Instant asOf) {
        return jdbc.sql("""
                SELECT source FROM depot_equity_snapshot
                 WHERE connection = :c AND granularity = 'DAILY' AND as_of = :a""")
                .param("c", connection)
                .param("a", java.sql.Timestamp.from(asOf))
                .query(String.class)
                .single();
    }

    @Test
    void reconstructedInsertIsLabelledReconstructed() {
        repo.upsertReconstructed("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(sourceOf("conn-1", DAY)).isEqualTo("RECONSTRUCTED");
    }

    @Test
    void reconstructedNeverOverwritesMeasured() {
        repo.upsert("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsertReconstructed("conn-1", DAY, "DAILY",
                        new BigDecimal("999.00"), new BigDecimal("999.00"), "EUR");

        assertThat(w).isEmpty();
        assertThat(repo.series("conn-1", "DAILY", DAY).getFirst().equity())
                .isEqualByComparingTo("100.00");
        assertThat(sourceOf("conn-1", DAY)).isEqualTo("MEASURED");
    }

    @Test
    void measuredRelabelsAReconstructedRow() {
        repo.upsertReconstructed("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsert("conn-1", DAY, "DAILY",
                        new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        // Identical numbers, different source: the write MUST happen, otherwise the day
        // keeps the wrong label forever.
        assertThat(w).isPresent();
        assertThat(sourceOf("conn-1", DAY)).isEqualTo("MEASURED");
    }

    @Test
    void reconstructedReplayWritesNothing() {
        repo.upsertReconstructed("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.upsertReconstructed("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR")).isEmpty();
    }

    @Test
    void firstMeasuredSkipsReconstructedRows() {
        Instant earlier = DAY.minus(java.time.Duration.ofDays(3));
        repo.upsertReconstructed("conn-1", earlier, "DAILY",
                new BigDecimal("90.00"), new BigDecimal("30.00"), "EUR");
        repo.upsert("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.firstMeasured("conn-1", "DAILY"))
                .isPresent()
                .get()
                .extracting(DepotEquitySnapshot::asOf)
                .isEqualTo(DAY);
    }

    @Test
    void firstMeasuredIgnoresOtherConnections() {
        repo.upsert("conn-2", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        assertThat(repo.firstMeasured("conn-1", "DAILY")).isEmpty();
    }

    @Test
    void reconstructedRerunWithNewNumbersCorrectsTheRow() {
        repo.upsertReconstructed("conn-1", DAY, "DAILY",
                new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR");

        Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                repo.upsertReconstructed("conn-1", DAY, "DAILY",
                        new BigDecimal("120.00"), new BigDecimal("50.00"), "EUR");

        // A backfill re-run after the book improves MUST correct the row, not skip it.
        assertThat(w).isPresent();
        assertThat(w.get().inserted()).isFalse();
        var row = repo.series("conn-1", "DAILY", DAY).getFirst();
        assertThat(row.equity()).isEqualByComparingTo("120.00");
        assertThat(row.cash()).isEqualByComparingTo("50.00");
        assertThat(row.positionsValue()).isEqualByComparingTo("70.00");
        assertThat(sourceOf("conn-1", DAY)).isEqualTo("RECONSTRUCTED");
    }

    @Test
    void deleteStaleReconstructedNeverTouchesAMeasuredRow() {
        // The one guarantee that makes this DELETE safe to run before every write: a MEASURED
        // row is a real observation of the account, and no reconstruction may remove it — not
        // even one dated before the anchor, which is what a backdated measurement or a manual
        // insert would look like.
        Instant measuredEarlier = DAY.minus(java.time.Duration.ofDays(3));
        Instant reconstructed = DAY.minus(java.time.Duration.ofDays(2));
        repo.upsert("conn-1", measuredEarlier, "DAILY",
                new BigDecimal("90.00"), new BigDecimal("30.00"), "EUR");
        repo.upsertReconstructed("conn-1", reconstructed, "DAILY",
                new BigDecimal("95.00"), new BigDecimal("35.00"), "EUR");

        int deleted = repo.deleteStaleReconstructedBefore("conn-1", "DAILY", DAY, List.of());

        assertThat(deleted).isEqualTo(1);
        assertThat(sourceOf("conn-1", measuredEarlier)).isEqualTo("MEASURED");
        assertThat(repo.series("conn-1", "DAILY", measuredEarlier))
                .extracting(DepotEquitySnapshot::asOf)
                .containsExactly(measuredEarlier);
    }

    @Test
    void deleteStaleReconstructedKeepsTheDaysHandedToIt() {
        Instant kept = DAY.minus(java.time.Duration.ofDays(2));
        Instant stale = DAY.minus(java.time.Duration.ofDays(4));
        repo.upsertReconstructed("conn-1", kept, "DAILY",
                new BigDecimal("95.00"), new BigDecimal("35.00"), "EUR");
        repo.upsertReconstructed("conn-1", stale, "DAILY",
                new BigDecimal("80.00"), new BigDecimal("20.00"), "EUR");

        int deleted = repo.deleteStaleReconstructedBefore("conn-1", "DAILY", DAY, List.of(kept));

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.series("conn-1", "DAILY", stale))
                .extracting(DepotEquitySnapshot::asOf)
                .containsExactly(kept);
    }

    @Test
    void deleteStaleReconstructedStopsAtTheAnchorAndAtTheConnection() {
        Instant afterAnchor = DAY.plus(java.time.Duration.ofDays(1));
        Instant beforeAnchor = DAY.minus(java.time.Duration.ofDays(1));
        repo.upsertReconstructed("conn-1", afterAnchor, "DAILY",
                new BigDecimal("95.00"), new BigDecimal("35.00"), "EUR");
        repo.upsertReconstructed("conn-2", beforeAnchor, "DAILY",
                new BigDecimal("95.00"), new BigDecimal("35.00"), "EUR");
        repo.upsertReconstructed("conn-1", beforeAnchor, "INTRADAY",
                new BigDecimal("95.00"), new BigDecimal("35.00"), "EUR");

        int deleted = repo.deleteStaleReconstructedBefore("conn-1", "DAILY", DAY, List.of());

        assertThat(deleted).isZero();
        assertThat(sourceOf("conn-1", afterAnchor)).isEqualTo("RECONSTRUCTED");
        assertThat(sourceOf("conn-2", beforeAnchor)).isEqualTo("RECONSTRUCTED");
    }
}
