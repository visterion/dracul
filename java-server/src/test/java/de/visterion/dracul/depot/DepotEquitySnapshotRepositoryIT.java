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
}
