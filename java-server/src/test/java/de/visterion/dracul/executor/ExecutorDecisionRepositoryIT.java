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
}
