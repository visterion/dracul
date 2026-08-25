package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionLegRepositoryTest {

    @Autowired ExecutorPositionLegRepository repo;
    @Autowired ExecutorPositionRepository positionRepo;
    @Autowired DecisionLogRepository decisionLogRepo;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void insertAndFindOpenLegs() {
        long positionId = insertTestPosition("ACME" + System.nanoTime(), new BigDecimal("20"));

        repo.insert(new ExecutorPositionLeg(null, positionId, 1, "ord-1", "stop-1",
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));
        long leg2 = repo.insert(new ExecutorPositionLeg(null, positionId, 2, "ord-2", "stop-2",
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));

        assertThat(repo.findOpenByPosition(positionId)).hasSize(2);

        repo.closeLeg(leg2, new BigDecimal("95"), "HARD_STOP",
                Instant.parse("2026-01-02T00:00:00Z"));

        List<ExecutorPositionLeg> open = repo.findOpenByPosition(positionId);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().tranche()).isEqualTo(1);
        assertThat(repo.findByPosition(positionId)).hasSize(2);

        ExecutorPositionLeg closed = repo.findByPosition(positionId).stream()
                .filter(l -> l.id() == leg2).findFirst().orElseThrow();
        assertThat(closed.status()).isEqualTo(ExecutorPositionLeg.CLOSED);
        assertThat(closed.exitPrice()).isEqualByComparingTo("95");
        assertThat(closed.exitReason()).isEqualTo("HARD_STOP");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.isOpen()).isFalse();
    }

    /**
     * Proves the V45 backfill against synthetic rows in a real Postgres (Testcontainers), rather
     * than trusting a production dump (which the repo must never carry, see the task brief): a
     * single-tranche and a two-tranche position are inserted, along with the ENTER decision row
     * the backfill joins on by {@code signal_id} (not by symbol -- see the migration comment for
     * why that distinction matters), and the same backfill statements V45 runs are re-run against
     * just those rows. The single-tranche leg keeps the full qty; the two-tranche legs split by
     * the ENTER decision's qty and the remainder, and the sum-check does not fire.
     */
    @Test
    void backfillSplitsTwoTrancheQtyByTheSignalJoinedEnterDecision() {
        String singleSymbol = "ACME1-" + UUID.randomUUID();
        String twoSymbol = "ACME2-" + UUID.randomUUID();
        String singleSignal = "sig-" + UUID.randomUUID();
        String twoSignal = "sig-" + UUID.randomUUID();

        long singleId = positionRepo.insert(openPosition(singleSymbol, singleSignal,
                new BigDecimal("10"), null, null));
        long twoId = positionRepo.insert(openPosition(twoSymbol, twoSignal,
                new BigDecimal("20"), "ord-2", "stop-2"));

        insertEnterDecision(twoSignal, new BigDecimal("12"));

        runBackfill(List.of(singleId, twoId));

        List<ExecutorPositionLeg> singleLegs = repo.findByPosition(singleId);
        assertThat(singleLegs).hasSize(1);
        assertThat(singleLegs.getFirst().tranche()).isEqualTo(1);
        assertThat(singleLegs.getFirst().qty()).isEqualByComparingTo("10");

        List<ExecutorPositionLeg> twoLegs = repo.findByPosition(twoId);
        assertThat(twoLegs).hasSize(2);
        assertThat(twoLegs.get(0).tranche()).isEqualTo(1);
        assertThat(twoLegs.get(0).qty()).isEqualByComparingTo("12");
        assertThat(twoLegs.get(1).tranche()).isEqualTo(2);
        assertThat(twoLegs.get(1).qty()).isEqualByComparingTo("8");

        BigDecimal sum = twoLegs.stream().map(ExecutorPositionLeg::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(positionRepo.findById(twoId).qty());
    }

    /**
     * The correction to the brief's backfill: a two-tranche position whose signal has no matching
     * ENTER row must abort the backfill, not fall back to {@code qty / 2}. The leg table's
     * {@code qty NOT NULL} constraint enforces this -- the missing ENTER row leaves
     * {@code enter_qty} NULL, and the INSERT fails.
     */
    @Test
    void backfillFailsLoudlyWhenTwoTranchePositionHasNoMatchingEnterRow() {
        String symbol = "ACME3-" + UUID.randomUUID();
        String signalId = "sig-" + UUID.randomUUID();
        long id = positionRepo.insert(openPosition(symbol, signalId,
                new BigDecimal("20"), "ord-2", "stop-2"));
        // Deliberately no decision_log ENTER row for this signal.

        assertThatThrownBy(() -> runBackfill(List.of(id)))
                .isInstanceOf(DataAccessException.class);
    }

    private long insertTestPosition(String symbol, BigDecimal qty) {
        return positionRepo.insert(openPosition(symbol, "sig-" + UUID.randomUUID(), qty, null, null));
    }

    private ExecutorPosition openPosition(String symbol, String signalId, BigDecimal qty,
            String tranche2OrderId, String tranche2StopOrderId) {
        return new ExecutorPosition(null, "depot-1", symbol, "BUY",
                qty, new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), tranche2OrderId == null ? 1 : 2, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), signalId, "strigoi-spin",
                null, null, "OPEN", "ord-1",
                null, null, 0, null, null, null, null, "stop-1",
                "Technology", null, tranche2OrderId, tranche2StopOrderId, 0, null, null,
                null, null, null, null, false);
    }

    private void insertEnterDecision(String signalId, BigDecimal tranche1Qty) {
        JsonNode orderJson = mapper.createObjectNode().put("qty", tranche1Qty);
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "v1", "SIGNAL", signalId,
                "strigoi-spin", "v1", null, null, null, "ENTER", null,
                orderJson, null, null, null, null));
    }

    /** Re-runs the exact two backfill INSERTs V45 runs, scoped to the given position ids so this
     *  test cannot touch positions created by other test classes sharing the reused container. */
    private void runBackfill(List<Long> positionIds) {
        jdbc.sql("""
                INSERT INTO executor_position_leg
                    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
                     exit_price, exit_reason, closed_at)
                SELECT p.id, 1, p.broker_order_id, p.stop_order_id,
                       CASE WHEN p.tranche2_order_id IS NULL THEN p.qty
                            ELSE t1.enter_qty END,
                       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
                            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
                            ELSE 'CLOSED' END,
                       p.exit_price, p.exit_reason, p.closed_at
                FROM executor_position p
                LEFT JOIN LATERAL (
                    SELECT (d.order_json ->> 'qty')::numeric AS enter_qty
                    FROM decision_log d
                    WHERE d.action = 'ENTER' AND d.signal_id = p.source_signal_id
                    ORDER BY d.ts_decision
                    LIMIT 1
                ) t1 ON TRUE
                WHERE p.id IN (:ids)
                """)
                .param("ids", positionIds)
                .update();

        jdbc.sql("""
                INSERT INTO executor_position_leg
                    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
                     exit_price, exit_reason, closed_at)
                SELECT p.id, 2, p.tranche2_order_id, p.tranche2_stop_order_id,
                       p.qty - l1.qty,
                       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
                            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
                            ELSE 'CLOSED' END,
                       p.exit_price, p.exit_reason, p.closed_at
                FROM executor_position p
                JOIN executor_position_leg l1 ON l1.position_id = p.id AND l1.tranche = 1
                WHERE p.tranche2_order_id IS NOT NULL AND p.id IN (:ids)
                """)
                .param("ids", positionIds)
                .update();
    }
}
