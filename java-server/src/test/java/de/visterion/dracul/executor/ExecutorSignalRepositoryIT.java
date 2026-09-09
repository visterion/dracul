package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JVM-zone coupling of {@code ExecutorSignal.createdAt}, which no string-literal unit test can
 * reach: the column is {@code TIMESTAMPTZ} and {@code mapRow} renders it with
 * {@code Timestamp.toString()}, i.e. in the JVM DEFAULT zone, while {@code TradingDays.ageOf}
 * compares against {@code LocalDate.now(clock)} in UTC.
 *
 * <p>The seeded instant is a TUESDAY at 00:30+00 on purpose: under a negative-offset JVM it
 * renders as the previous MONDAY, which is a trading day, so the computed age becomes 1 instead
 * of 0 and this test fails. A weekend date would pass in both zones and would be vacuous.
 *
 * <p>What actually removes the hazard is {@code java-server/Dockerfile}'s
 * {@code -Duser.timezone=UTC}; the image build skips tests and CI runs on the GitHub runner, so
 * this assertion pins the runner's zone, never the runtime's. It is the tripwire, not the fix.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorSignalRepositoryIT {

    /** Tuesday. */
    private static final Clock TUESDAY =
            Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @Autowired ExecutorSignalRepository repo;
    @Autowired JdbcClient jdbc;

    @Test
    void aSignalEmittedJustAfterMidnightUtcIsZeroTradingDaysOldOnTheSameDay() {
        // ExecutorSignalRepository.insert has no created_at parameter (the column is DB-defaulted),
        // so the row is seeded with raw SQL to pin the instant.
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO executor_signal
                  (signal_id, source, agent_version, symbol, direction, confidence, mechanism,
                   horizon, reference_price, status, created_at)
                VALUES (:id, 'strigoi-spin', 'v1', 'TZCO', 'BUY', 0.7, 'SPINOFF',
                        '3m', 100, 'PENDING', TIMESTAMPTZ '2026-09-08 00:30:00+00')
                """)
                .param("id", id)
                .update();

        List<ExecutorSignal> pending = repo.findPending(Integer.MAX_VALUE);
        ExecutorSignal seeded = pending.stream()
                .filter(s -> id.equals(s.signalId()))
                .findFirst().orElseThrow();

        assertThat(TradingDays.ageOf(seeded.createdAt(), TUESDAY))
                .as("age of a 00:30 UTC Tuesday emission, read back on that Tuesday")
                .isZero();

        jdbc.sql("DELETE FROM executor_signal WHERE signal_id = :id").param("id", id).update();
    }
}
