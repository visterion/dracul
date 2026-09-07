package de.visterion.dracul.outcome;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empty-DB sanity check for the Task-10 analytics aggregates: every query must return an empty
 * list (not throw / not 500) when {@code outcome_log}/{@code decision_log} have no matching rows.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class OutcomeLogRepositoryAnalyticsIT {

    @Autowired OutcomeLogRepository repo;
    @Autowired CalibrationService calibration;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** "Empty DB" has to be MADE empty. {@code ContainerConfig} reuses one Postgres container
     *  across IT classes, so a sibling that writes {@code outcome_log}/{@code decision_log}
     *  (e.g. {@code VersionMetricsRepositoryIT}) leaves rows behind and every assertion here
     *  fails — a latent order-dependence that only shows up once class ordering shifts. Ten
     *  sibling ITs clear their tables for exactly this reason. */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outcome_log").update();
        jdbc.sql("DELETE FROM decision_log").update();
    }

    @Test
    void emptyDbYieldsEmptyAggregatesNotErrors() {
        assertThat(repo.findExecutorBrierPoints()).isEmpty();
        assertThat(repo.findHunterBrierPoints()).isEmpty();
        assertThat(repo.findVetoRows()).isEmpty();
        assertThat(repo.findHardTriggerLatencySeconds()).isEmpty();
        assertThat(repo.findWhipsawFlags()).isEmpty();
        assertThat(repo.findStopBasisRows()).isEmpty();
        assertThat(repo.findSlippageValues()).isEmpty();
    }

    @Test
    void emptyDbCalibrationResponseIsInsufficientNotError() {
        var executor = calibration.brierResult(repo.findExecutorBrierPoints());
        assertThat(executor.n()).isEqualTo(0);
        assertThat(executor.insufficient()).isTrue();
        assertThat(executor.buckets()).isEmpty();

        assertThat(calibration.hunterBrierResults(repo.findHunterBrierPoints())).isEmpty();
    }

    @Test
    void emptyDbBehaviorResponseIsZeroedNotError() {
        assertThat(calibration.vetoPrecision(repo.findVetoRows())).isEmpty();

        var latency = calibration.latency(repo.findHardTriggerLatencySeconds());
        assertThat(latency.n()).isEqualTo(0);

        var whipsaw = calibration.whipsaw(repo.findWhipsawFlags());
        assertThat(whipsaw.reentryWithin10d()).isEqualTo(0);
        assertThat(whipsaw.roundtripUnder5d()).isEqualTo(0);

        assertThat(calibration.stopBasisStats(repo.findStopBasisRows())).isEmpty();

        var slippage = calibration.slippage(repo.findSlippageValues());
        assertThat(slippage.n()).isEqualTo(0);
    }

    private void seedDecisionLog(String logId, String signalId, String symbol, String reason) {
        jdbc.sql("""
                INSERT INTO decision_log (log_id, run_id, rule_version, trigger_type, signal_id,
                                          source_agent, symbol, action, reason_code)
                VALUES (CAST(:logId AS uuid), 'run-1', 'exec-v0.6', 'SIGNAL', :signalId,
                        'strigoi-spin', :symbol, 'REJECT', :reason)
                """)
                .param("logId", logId).param("signalId", signalId)
                .param("symbol", symbol).param("reason", reason)
                .update();
    }

    private void seedCounterfactual(String logIdRef, String symbol, String reason,
            String r20, boolean complete, String computedAt) {
        jdbc.sql("""
                INSERT INTO outcome_log (kind, log_id_ref, symbol, reason_code, hypothetical,
                                         source_agent, complete, computed_at)
                VALUES ('COUNTERFACTUAL', :ref, :symbol, :reason,
                        CAST(:hypo AS jsonb), 'strigoi-spin', :complete,
                        CAST(:computedAt AS timestamptz))
                """)
                .param("ref", logIdRef).param("symbol", symbol).param("reason", reason)
                .param("hypo", "{\"r_after_20d\":" + r20
                        + ",\"r_after_60d\":null,\"would_have_stopped_out\":false,\"skipped_reason\":null}")
                .param("complete", complete).param("computedAt", computedAt)
                .update();
    }

    /** The ISRG shape: place_entry retried the SAME signal, so one signal produced several
     *  counterfactual rows and inflated veto_precision. One row per (signal, reason) survives --
     *  the complete one if there is one, otherwise the most recently computed. */
    @Test
    void threeRowsForOneSignalCollapseToTheCompleteOne() {
        String signalId = "sig-dupe";
        String a = java.util.UUID.randomUUID().toString();
        String b = java.util.UUID.randomUUID().toString();
        String c = java.util.UUID.randomUUID().toString();
        seedDecisionLog(a, signalId, "DUPCO", "COOLDOWN");
        seedDecisionLog(b, signalId, "DUPCO", "COOLDOWN");
        seedDecisionLog(c, signalId, "DUPCO", "COOLDOWN");
        seedCounterfactual(a, "DUPCO", "COOLDOWN", "0.10", false, "2026-09-06T21:00:00Z");
        seedCounterfactual(b, "DUPCO", "COOLDOWN", "0.20", false, "2026-09-06T21:00:00Z");
        seedCounterfactual(c, "DUPCO", "COOLDOWN", "1.50", true,  "2026-08-01T21:00:00Z");

        var rows = repo.findVetoRows();

        assertThat(rows).hasSize(1);
        // The complete row wins even though it is the OLDEST -- completeness outranks recency.
        assertThat(rows.getFirst().rAfter20d()).isEqualTo(1.50);
    }

    @Test
    void distinctSignalsOnTheSameSymbolAllSurvive() {
        // P (4 rows / 4 signals) and PAYO (3 / 3) are genuinely distinct signals. Keying on the
        // SYMBOL instead of the signal would have collapsed them and understated the counts.
        for (int i = 0; i < 4; i++) {
            String logId = java.util.UUID.randomUUID().toString();
            seedDecisionLog(logId, "sig-distinct-" + i, "MULTICO", "PACE_LIMIT");
            seedCounterfactual(logId, "MULTICO", "PACE_LIMIT", "0.5", true, "2026-09-06T21:00:00Z");
        }

        assertThat(repo.findVetoRows()).hasSize(4);
    }

    @Test
    void llmSkipRowsHaveNoDecisionLogPartnerAndKeyOnTheirOwnRef() {
        seedCounterfactual("skip:sig-a", "SKIPCO", "LLM_SKIP", "0.7", true, "2026-09-06T21:00:00Z");
        seedCounterfactual("skip:sig-b", "SKIPCO", "LLM_SKIP", "0.9", true, "2026-09-06T21:00:00Z");

        var rows = repo.findVetoRows();

        // Two LLM_SKIP rows on the SAME symbol with different signal ids must both come back --
        // the "skip:" ref is already one per signal, so COALESCE keys on it directly.
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.reasonCode()).isEqualTo("LLM_SKIP"));
    }

    @Test
    void brierAndStopBasisQueriesAreUnaffectedBySkipRows() {
        seedCounterfactual("skip:sig-c", "SKIPCO", "LLM_SKIP", "0.7", true, "2026-09-06T21:00:00Z");

        // Both inner-join decision_log, which a "skip:" ref has no partner in.
        assertThat(repo.findHunterBrierPoints()).isEmpty();
        assertThat(repo.findStopBasisRows()).isEmpty();
        assertThat(repo.findExecutorBrierPoints()).isEmpty();
    }
}
