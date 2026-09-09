package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the selector that feeds the LLM_SKIP counterfactual loop. Every predicate in it exists
 *  because of a row shape that would otherwise be mis-counted. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorDecisionRepositoryIT {

    @Autowired ExecutorDecisionRepository repo;
    @Autowired ExecutorSignalRepository signals;
    @Autowired DecisionLogRepository decisionLog;
    @Autowired JdbcClient jdbc;

    /** ContainerConfig reuses one Postgres across IT classes, so siblings leave rows behind. */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outcome_log").update();
        jdbc.sql("DELETE FROM decision_log").update();
        jdbc.sql("DELETE FROM executor_decision").update();
        jdbc.sql("DELETE FROM executor_signal").update();
    }

    private String seedSignal(String symbol, LocalDate barDate, BigDecimal atr) {
        String id = UUID.randomUUID().toString();
        signals.insert(new ExecutorSignal(id, "strigoi-spin", "v1", symbol, "BUY", 0.7,
                "SPINOFF", List.of(), "3m", new BigDecimal("101.5"), "SKIPPED", null,
                null, null, barDate, atr));
        return id;
    }

    private void seedDecision(String signalId, String symbol, String action, String rejectReason) {
        repo.insert(new ExecutorDecision(null, signalId, symbol, false, rejectReason, List.of(),
                "rationale", null, "run-1", null, action));
    }

    @Test
    void selectsOnlySkipsWithReferenceInputsAndNoRejectDecisionLogRow() {
        String wanted = seedSignal("SKIPCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(wanted, "SKIPCO", "SKIP", null);

        // (a) a SKIP whose signal was ALSO vetoed by place_entry: the veto reason wins, the LLM's
        // SKIP must not be counted a second time.
        String vetoed = seedSignal("VETOCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(vetoed, "VETOCO", "SKIP", null);
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "SIGNAL", vetoed,
                "strigoi-spin", "v1", "VETOCO", null, null, "REJECT", "MAX_POSITIONS",
                null, null, null, null, null));

        // (b) a HOLD is not a skip.
        String held = seedSignal("HOLDCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(held, "HOLDCO", "HOLD", null);

        // (c) a row carrying a reject_reason is a code-gate row, not an LLM verdict.
        String gated = seedSignal("GATECO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(gated, "GATECO", "SKIP", "COOLDOWN");

        // (d) pre-V49 shape: no reference inputs, therefore never selected and never backfilled.
        String legacy = seedSignal("OLDCO", null, null);
        seedDecision(legacy, "OLDCO", "SKIP", null);

        List<ExecutorDecision> found = repo.findSkipsWithoutDecisionLog();

        assertThat(found).extracting(ExecutorDecision::signalId).containsExactly(wanted);
    }

    @Test
    void aDecisionLogRowWithANonRejectActionDoesNotExcludeTheSkip() {
        // ADD_TRANCHE_REJECT (SP3) and ADD_TRANCHE are not what processCounterfactuals consumes,
        // so a signal carrying only those must not fall out of BOTH loops.
        String id = seedSignal("TRNCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(id, "TRNCO", "SKIP", null);
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "SIGNAL", id,
                "strigoi-spin", "v1", "TRNCO", null, null, "ADD_TRANCHE_REJECT", "BROKER_ERROR",
                null, "broker call failed: synthetic", null, null, null));

        assertThat(repo.findSkipsWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(id);
    }

    @Test
    void ordersByCreatedAtAscending() throws Exception {
        String first = seedSignal("ONECO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(first, "ONECO", "SKIP", null);
        Thread.sleep(10);
        String second = seedSignal("TWOCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(second, "TWOCO", "SKIP", null);

        assertThat(repo.findSkipsWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(first, second);
    }

    // =========================================================================
    // findSweptWithoutDecisionLog — the SIGNAL_EXPIRED_UNEVALUATED population: signals the
    // sweeper retired that the LLM never called place_entry on. A signal stranded by a TRANSIENT
    // place_entry reject already has a processReject counterfactual under that veto reason and
    // must NOT appear here — the veto reason wins, exactly as it does for LLM_SKIPs.
    // =========================================================================

    private String seedPendingSignal(String symbol, LocalDate barDate, BigDecimal atr) {
        String id = UUID.randomUUID().toString();
        signals.insert(new ExecutorSignal(id, "strigoi-spin", "v1", symbol, "BUY", 0.7,
                "SPINOFF", List.of(), "3m", new BigDecimal("101.5"), "REJECTED", null,
                null, null, barDate, atr));
        return id;
    }

    private void seedSweepRow(String signalId, String symbol) {
        repo.insert(new ExecutorDecision(null, signalId, symbol, false, "SIGNAL_EXPIRED",
                List.of("SIGNAL_EXPIRED:FAIL (6 > 5 days)"),
                PendingSignalSweeper.RATIONALE_PREFIX + "6 > 5 trading days",
                null, "run-1", null, PendingSignalSweeper.ACTION));
    }

    @Test
    void selectsOnlySweptSignalsWithAnchorsAndNoRejectDecisionLogRow() {
        String wanted = seedPendingSignal("SWEPTCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedSweepRow(wanted, "SWEPTCO");

        // (a) pre-V49 shape: no reference_atr, so the walk has no anchor. Forward-only, no backfill.
        String noAtr = seedPendingSignal("NOATRCO", LocalDate.parse("2026-09-04"), null);
        seedSweepRow(noAtr, "NOATRCO");

        // (b) place_entry's OWN SIGNAL_EXPIRED row: action is null there, and its counterfactual
        // comes from the decision_log REJECT partner, not from here.
        String placeEntry = seedPendingSignal("PECO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedDecision(placeEntry, "PECO", null, "SIGNAL_EXPIRED");

        // (c) stranded by a TRANSIENT place_entry reject: it already has a processReject
        // counterfactual under MAX_POSITIONS. The veto reason wins.
        String vetoed = seedPendingSignal("VETOSWEPT", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedSweepRow(vetoed, "VETOSWEPT");
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "SIGNAL", vetoed,
                "strigoi-spin", "v1", "VETOSWEPT", null, null, "REJECT", "MAX_POSITIONS",
                null, null, null, null, null));

        assertThat(repo.findSweptWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(wanted);
    }

    /** The accepting interleaving: the sweep marked REJECTED while an in-flight place_entry,
     *  already past its DUPLICATE check, booked the position and logged SIGNAL/ENTER. The row
     *  still satisfies the finder (NOT EXISTS looks for REJECT, not ENTER) — what keeps the
     *  hypothetical away from the real trade is OutcomeBatchJob's ACCEPTED guard, not this SQL.
     *  An implementation that widens NOT EXISTS to any SIGNAL row fails here. */
    @Test
    void aSignalWhoseOnlyDecisionLogPartnerIsAnEnterIsStillReturned() {
        String id = seedPendingSignal("ENTERCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedSweepRow(id, "ENTERCO");
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "SIGNAL", id,
                "strigoi-spin", "v1", "ENTERCO", null, null, "ENTER", null,
                null, null, null, null, null));

        assertThat(repo.findSweptWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(id);
    }

    /** A MAINTENANCE/CANCEL_EXPIRED partner is a different trigger_type entirely and must not
     *  drop the signal out of BOTH counterfactual loops. */
    @Test
    void aSignalWhoseOnlyDecisionLogPartnerIsAMaintenanceRowIsStillReturned() {
        String id = seedPendingSignal("MAINTCO", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedSweepRow(id, "MAINTCO");
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "MAINTENANCE", id,
                "strigoi-spin", "v1", "MAINTCO", null, null, "CANCEL_EXPIRED", "SIGNAL_EXPIRED",
                null, null, null, null, null));

        assertThat(repo.findSweptWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(id);
    }

    /** One signal can carry TWO sweep rows (a markStatus failure after the insert, or an operator
     *  pass overlapping the agent's). DISTINCT ON collapses them to the earliest. */
    @Test
    void twoSweepRowsForOneSignalCollapseToOne() {
        String id = seedPendingSignal("DUPSWEEP", LocalDate.parse("2026-09-04"), new BigDecimal("3.1"));
        seedSweepRow(id, "DUPSWEEP");
        seedSweepRow(id, "DUPSWEEP");

        assertThat(repo.findSweptWithoutDecisionLog())
                .extracting(ExecutorDecision::signalId).containsExactly(id);
    }
}
