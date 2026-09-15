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
     *  sibling ITs clear their tables for exactly this reason. {@code executor_signal} joins in
     *  too now ({@code findVetoRows}' ACCEPTED exclusion), and it carries no incoming FK, so it
     *  is cleared here as well — without it, a sibling IT that ever inserts a
     *  {@code sig-a}/{@code sig-dupe}/{@code sig-distinct-*}/{@code sig-nonexistent} row would
     *  silently change what the pre-existing tests below see. */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outcome_log").update();
        jdbc.sql("DELETE FROM decision_log").update();
        jdbc.sql("DELETE FROM executor_signal").update();
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
    void anUnlabelledSkipRowStillYieldsNoHunterBrierPoint() {
        seedSignal("sig-c", "SKIPCO", "SKIPPED");
        seedCounterfactual("skip:sig-c", "SKIPCO", "LLM_SKIP", "0.7", true, "2026-09-06T21:00:00Z");

        // The row carries no hunter_label; a labelled one counts since SP7 -- see test
        // aLabelledLlmSkipRowIsOneHunterBrierPoint. Its executor_signal row IS seeded, so the
        // missing label is the only thing keeping it out of the hunter Brier.
        assertThat(repo.findHunterBrierPoints()).isEmpty();
        assertThat(repo.findStopBasisRows()).isEmpty();
        assertThat(repo.findExecutorBrierPoints()).isEmpty();
    }

    private void seedSignal(String signalId, String symbol, String status) {
        seedSignalRow(signalId, symbol, status, "strigoi-spin", "0.7");
    }

    /** Same row with an explicit hunter, for the assertion that the agent comes from
     *  {@code executor_signal.source} and not from {@code outcome_log.source_agent}. */
    private void seedSignalWithSource(String signalId, String symbol, String status, String source) {
        seedSignalRow(signalId, symbol, status, source, "0.7");
    }

    /** Same row with an explicit confidence, for the bucket-edge assertion. */
    private void seedSignalWithConfidence(String signalId, String symbol, String status,
            String confidence) {
        seedSignalRow(signalId, symbol, status, "strigoi-spin", confidence);
    }

    private void seedSignalRow(String signalId, String symbol, String status, String source,
            String confidence) {
        jdbc.sql("""
                INSERT INTO executor_signal
                  (signal_id, source, agent_version, symbol, direction, confidence, mechanism,
                   horizon, reference_price, status)
                VALUES (:id, :source, 'v1', :symbol, 'BUY', CAST(:confidence AS numeric), 'SPINOFF',
                        '3m', 100, :status)
                ON CONFLICT (signal_id) DO UPDATE SET status = EXCLUDED.status,
                                                      source = EXCLUDED.source,
                                                      confidence = EXCLUDED.confidence
                """)
                .param("id", signalId).param("symbol", symbol).param("status", status)
                .param("source", source).param("confidence", confidence)
                .update();
    }

    /** An ACCEPTED signal has a position, and its "what if we had taken it" is answered by the
     *  TRADE row once that position closes. Counting a hypothetical next to a live real position
     *  would be the worse error, so the counterfactual is WITHHELD from veto_precision (an n can
     *  drop without a matching n gain elsewhere while the position is still open). */
    @Test
    void aCounterfactualForAnAcceptedSignalLeavesVetoPrecision() {
        String logId = java.util.UUID.randomUUID().toString();
        seedSignal("sig-accepted", "ACCCO", "ACCEPTED");
        seedDecisionLog(logId, "sig-accepted", "ACCCO", "BROKER_ERROR");
        seedCounterfactual(logId, "ACCCO", "BROKER_ERROR", "0.5", true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows()).isEmpty();
    }

    /** EXPIRED means the entry bracket was placed and never filled — no trade happened, so the
     *  counterfactual is still the only answer there is. Same for REJECTED and SKIPPED. */
    @Test
    void theSameRowWithAnExpiredSignalStays() {
        String logId = java.util.UUID.randomUUID().toString();
        seedSignal("sig-expired", "EXPCO", "EXPIRED");
        seedDecisionLog(logId, "sig-expired", "EXPCO", "PACE_LIMIT");
        seedCounterfactual(logId, "EXPCO", "PACE_LIMIT", "0.5", true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows()).extracting("reasonCode").containsExactly("PACE_LIMIT");
    }

    /** The signal-anchored rows have no decision_log partner and resolve their signal id out of
     *  the log_id_ref prefix instead. skip:<id> for an ACCEPTED signal drops out too. */
    @Test
    void aSkipRowForAnAcceptedSignalAlsoLeavesVetoPrecision() {
        seedSignal("sig-skip-acc", "SKACC", "ACCEPTED");
        seedCounterfactual("skip:sig-skip-acc", "SKACC", "LLM_SKIP", "0.5", true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows()).isEmpty();
    }

    /** expired:<id> for a REJECTED signal is the normal swept case and must survive, keeping its
     *  own reason code (never pooled with place_entry's SIGNAL_EXPIRED). */
    @Test
    void anExpiredRowForARejectedSignalSurvivesWithItsOwnReasonCode() {
        seedSignal("sig-swept", "SWPCO", "REJECTED");
        seedCounterfactual("expired:sig-swept", "SWPCO", "SIGNAL_EXPIRED_UNEVALUATED", "0.5",
                true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows())
                .extracting("reasonCode").containsExactly("SIGNAL_EXPIRED_UNEVALUATED");
    }

    /** Pins the {@code substr(ol.log_id_ref, 9)} offset for the {@code expired:} prefix: a wrong
     *  offset would mis-slice the id, resolve to no {@code executor_signal} row, and the
     *  {@code s.status IS NULL} arm of the guard would then keep this row — passing the suite
     *  while silently disabling the ACCEPTED exclusion for the whole
     *  {@code SIGNAL_EXPIRED_UNEVALUATED} population. Asserting absence here forces the offset
     *  to resolve to the real, ACCEPTED signal. */
    @Test
    void anExpiredRowForAnAcceptedSignalAlsoLeavesVetoPrecision() {
        seedSignal("sig-expired-acc", "EXPACC", "ACCEPTED");
        seedCounterfactual("expired:sig-expired-acc", "EXPACC", "SIGNAL_EXPIRED_UNEVALUATED",
                "0.5", true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows()).isEmpty();
    }

    /** s.status IS NULL keeps rows whose signal id no longer resolves, exactly as before. */
    @Test
    void aRowWhoseSignalIdResolvesToNothingIsUnaffected() {
        seedCounterfactual("skip:sig-nonexistent", "GHOSTCO", "LLM_SKIP", "0.5",
                true, "2026-09-01T00:00:00Z");

        assertThat(repo.findVetoRows()).extracting("reasonCode").containsExactly("LLM_SKIP");
    }

    /** A hunter-Brier-visible decision row. Since SP7 the hunter Brier joins
     *  {@code executor_signal} for the confidence; the snapshot confidence here is deliberately a
     *  <i>different</i> number (0.9 vs the signal's 0.7) so
     *  {@code predictedComesFromTheSignalNotTheDecisionRow} can tell the two sources apart. */
    private void seedDecisionLogWithConfidence(String logId, String signalId, String symbol,
            String reason) {
        seedDecisionLogWithConfidence(logId, signalId, symbol, reason, "0.9",
                java.time.Instant.now().toString());
    }

    /** Explicit {@code created_at}: it is the primary tiebreak of the SP7 hunter-Brier query
     *  (the attempt closest to emission wins). */
    private void seedDecisionLogWithConfidence(String logId, String signalId, String symbol,
            String reason, String snapshotConfidence, String createdAt) {
        jdbc.sql("""
                INSERT INTO decision_log (log_id, run_id, rule_version, trigger_type, signal_id,
                                          source_agent, symbol, action, reason_code,
                                          inputs_snapshot, created_at)
                VALUES (CAST(:logId AS uuid), 'run-1', 'exec-v0.6', 'SIGNAL', :signalId,
                        'strigoi-spin', :symbol, 'REJECT', :reason,
                        CAST(('{"signal_confidence":' || :confidence || '}') AS jsonb),
                        CAST(:createdAt AS timestamptz))
                """)
                .param("logId", logId).param("signalId", signalId)
                .param("symbol", symbol).param("reason", reason)
                .param("confidence", snapshotConfidence).param("createdAt", createdAt)
                .update();
    }

    private void seedLabelledCounterfactual(String logIdRef, String symbol, String reason) {
        seedLabelledCounterfactual(logIdRef, symbol, reason, true,
                java.time.Instant.now().toString(), "strigoi-spin");
    }

    /** Explicit label, {@code computed_at} and {@code source_agent}: the label drives the
     *  win/loss assertions, {@code computed_at} is the secondary tiebreak, and
     *  {@code source_agent} must be allowed to DISAGREE with {@code executor_signal.source}
     *  so the agent column can be pinned to the signal (SP7). */
    private void seedLabelledCounterfactual(String logIdRef, String symbol, String reason,
            boolean hunterLabel, String computedAt, String sourceAgent) {
        jdbc.sql("""
                INSERT INTO outcome_log (kind, log_id_ref, symbol, reason_code, hypothetical,
                                         source_agent, hunter_label, complete, computed_at)
                VALUES ('COUNTERFACTUAL', :ref, :symbol, :reason,
                        CAST('{"r_after_20d":1.0,"r_after_60d":null,
                               "would_have_stopped_out":false,"skipped_reason":null}' AS jsonb),
                        :sourceAgent, :hunterLabel, false, CAST(:computedAt AS timestamptz))
                """)
                .param("ref", logIdRef).param("symbol", symbol).param("reason", reason)
                .param("sourceAgent", sourceAgent).param("hunterLabel", hunterLabel)
                .param("computedAt", computedAt)
                .update();
    }

    /** A TRADE outcome row: no {@code hunter_label}, so it must never reach the hunter Brier.
     *  No other helper here writes a TRADE row. */
    private void seedTradeRow(String logIdRef, String symbol) {
        jdbc.sql("""
                INSERT INTO outcome_log (kind, log_id_ref, symbol, source_agent, complete,
                                         computed_at)
                VALUES ('TRADE', :ref, :symbol, 'strigoi-spin', true, now())
                """)
                .param("ref", logIdRef).param("symbol", symbol)
                .update();
    }

    /**
     * STALE_FILL and ADOPTION_AMBIGUOUS are not vetos — nothing was judged and rejected on merit,
     * so they must not appear in veto_precision or its skipped counts. They MUST stay in the
     * hunter Brier: it asks whether the SIGNAL was good, and for a STALE_FILL a real trade is
     * known to have happened.
     */
    @Test
    void theAdoptionReasonCodesLeaveVetoPrecisionButStayInTheHunterBrier() {
        String staleLog = java.util.UUID.randomUUID().toString();
        String ambiguousLog = java.util.UUID.randomUUID().toString();
        String pacedLog = java.util.UUID.randomUUID().toString();
        seedSignal("sig-stale", "STALECO", "REJECTED");
        seedSignal("sig-ambiguous", "AMBCO", "PENDING");
        seedSignal("sig-paced", "PACECO", "REJECTED");
        seedDecisionLogWithConfidence(staleLog, "sig-stale", "STALECO", "STALE_FILL");
        seedDecisionLogWithConfidence(ambiguousLog, "sig-ambiguous", "AMBCO", "ADOPTION_AMBIGUOUS");
        seedDecisionLogWithConfidence(pacedLog, "sig-paced", "PACECO", "PACE_LIMIT");
        seedLabelledCounterfactual(staleLog, "STALECO", "STALE_FILL");
        seedLabelledCounterfactual(ambiguousLog, "AMBCO", "ADOPTION_AMBIGUOUS");
        seedLabelledCounterfactual(pacedLog, "PACECO", "PACE_LIMIT");

        assertThat(repo.findVetoRows()).extracting(CalibrationService.VetoRow::reasonCode)
                .containsExactly("PACE_LIMIT");
        assertThat(calibration.vetoPrecision(repo.findVetoRows()))
                .extracting(CalibrationService.VetoPrecision::reasonCode)
                .doesNotContain("STALE_FILL", "ADOPTION_AMBIGUOUS");

        assertThat(repo.findHunterBrierPoints()).hasSize(3);
    }

    /** P1 -- an LLM_SKIP counterfactual has no decision_log partner at all. Before SP7 the inner
     *  join dropped it; now the signal id comes out of the "skip:" prefix. */
    @Test
    void aLabelledLlmSkipRowIsOneHunterBrierPoint() {
        seedSignal("sig-skip", "SKIPCO", "SKIPPED");
        seedLabelledCounterfactual("skip:sig-skip", "SKIPCO", "LLM_SKIP");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().agent()).isEqualTo("strigoi-spin");
        assertThat(points.getFirst().predicted()).isEqualTo(0.7);
        assertThat(points.getFirst().won()).isTrue();
    }

    /** P1 -- the same for a swept signal, whose ref carries the "expired:" prefix (substr offset
     *  9, not 6). A wrong offset resolves to no signal and the point disappears. */
    @Test
    void aLabelledSweptRowIsOneHunterBrierPoint() {
        seedSignal("sig-swept-brier", "SWPCO", "REJECTED");
        seedLabelledCounterfactual("expired:sig-swept-brier", "SWPCO",
                "SIGNAL_EXPIRED_UNEVALUATED");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().agent()).isEqualTo("strigoi-spin");
        assertThat(points.getFirst().predicted()).isEqualTo(0.7);
        assertThat(points.getFirst().won()).isTrue();
    }

    /** P1 -- the prediction is the emission-time confidence on executor_signal (0.7), not the
     *  decision row's inputs_snapshot.signal_confidence (0.9). The two numbers differ on purpose. */
    @Test
    void predictedComesFromTheSignalNotTheDecisionRow() {
        String logId = java.util.UUID.randomUUID().toString();
        seedSignal("sig-src", "SRCCO", "REJECTED");
        seedDecisionLogWithConfidence(logId, "sig-src", "SRCCO", "PACE_LIMIT");
        seedLabelledCounterfactual(logId, "SRCCO", "PACE_LIMIT");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().predicted()).isEqualTo(0.7);
    }

    /** P1 -- place_entry retries wrote one counterfactual each. The hunter made ONE prediction
     *  for the signal, so the retries must collapse to one point. Both outcome rows are needed:
     *  with only one, the test would pass with or without DISTINCT ON. */
    @Test
    void retriesOfOneSignalCollapseToOnePoint() {
        String first = java.util.UUID.randomUUID().toString();
        String second = java.util.UUID.randomUUID().toString();
        seedSignal("sig-retry", "RETCO", "REJECTED");
        seedDecisionLogWithConfidence(first, "sig-retry", "RETCO", "COOLDOWN");
        seedDecisionLogWithConfidence(second, "sig-retry", "RETCO", "COOLDOWN");
        seedLabelledCounterfactual(first, "RETCO", "COOLDOWN");
        seedLabelledCounterfactual(second, "RETCO", "COOLDOWN");

        assertThat(repo.findHunterBrierPoints()).hasSize(1);
    }

    /** P1 -- a ref whose signal id resolves to nothing drops out of the inner join: no point,
     *  no error. (Deliberately seeds NO executor_signal row.) */
    @Test
    void aSkipRowWhoseSignalIsUnknownYieldsNoPoint() {
        seedLabelledCounterfactual("skip:sig-nonexistent", "GHOSTCO", "LLM_SKIP");

        assertThat(repo.findHunterBrierPoints()).isEmpty();
    }

    /** P1 -- hunter_label = false is a loss, not an absence. Pins that the label is read, not
     *  assumed true. */
    @Test
    void aLabelledFalseRowIsCountedAsALoss() {
        seedSignal("sig-loss", "LOSSCO", "SKIPPED");
        seedLabelledCounterfactual("skip:sig-loss", "LOSSCO", "LLM_SKIP", false,
                "2026-09-01T00:00:00Z", "strigoi-spin");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().won()).isFalse();
    }

    /** P1 -- the agent is executor_signal.source, so the grouping key cannot depend on which
     *  duplicate outcome row wins. The outcome row deliberately names a different agent. */
    @Test
    void theAgentComesFromTheSignalNotTheOutcomeRow() {
        seedSignalWithSource("sig-agent", "AGTCO", "SKIPPED", "strigoi-echo");
        seedLabelledCounterfactual("skip:sig-agent", "AGTCO", "LLM_SKIP", true,
                "2026-09-01T00:00:00Z", "strigoi-spin");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().agent()).isEqualTo("strigoi-echo");
    }

    /** P2 -- the tiebreak is load-bearing: the prediction is emission-time confidence, so the
     *  label of the attempt CLOSEST TO EMISSION is the fairest pair. The later attempt has the
     *  newer computed_at and the opposite label, so a computed_at-first order would flip this. */
    @Test
    void theRetainedPointIsTheAttemptClosestToEmission() {
        String early = java.util.UUID.randomUUID().toString();
        String late = java.util.UUID.randomUUID().toString();
        seedSignal("sig-tie", "TIECO", "REJECTED");
        seedDecisionLogWithConfidence(early, "sig-tie", "TIECO", "COOLDOWN", "0.9",
                "2026-07-17T14:00:00Z");
        seedDecisionLogWithConfidence(late, "sig-tie", "TIECO", "COOLDOWN", "0.9",
                "2026-07-27T14:00:00Z");
        seedLabelledCounterfactual(early, "TIECO", "COOLDOWN", true,
                "2026-08-01T00:00:00Z", "strigoi-spin");
        seedLabelledCounterfactual(late, "TIECO", "COOLDOWN", false,
                "2026-09-01T00:00:00Z", "strigoi-spin");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().won()).isTrue();
    }

    /** P2 -- mixed-shape signal: an emission-anchored skip row plus a later place_entry REJECT.
     *  NULLS FIRST puts the skip row (no decision partner, so a NULL created_at) ahead of every
     *  attempt, so the skip row's label wins even though it is the older computed_at. */
    @Test
    void aSignalWithBothASkipRowAndARejectRowYieldsThePointOfTheSkipRow() {
        String logId = java.util.UUID.randomUUID().toString();
        seedSignal("sig-both", "BOTHCO", "REJECTED");
        seedLabelledCounterfactual("skip:sig-both", "BOTHCO", "LLM_SKIP", false,
                "2026-08-01T00:00:00Z", "strigoi-spin");
        seedDecisionLogWithConfidence(logId, "sig-both", "BOTHCO", "COOLDOWN", "0.9",
                "2026-08-15T14:00:00Z");
        seedLabelledCounterfactual(logId, "BOTHCO", "COOLDOWN", true,
                "2026-09-01T00:00:00Z", "strigoi-spin");

        var points = repo.findHunterBrierPoints();

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().won()).isFalse();
    }

    /** P3 -- regression guard only: a TRADE row carries no hunter_label, so it is excluded with
     *  or without the explicit kind = 'COUNTERFACTUAL' clause. The clause keeps the population
     *  readable and independent of that incidental fact. */
    @Test
    void aTradeRowNeverReachesTheHunterBrier() {
        String logId = java.util.UUID.randomUUID().toString();
        seedSignal("sig-trade", "TRDCO", "ACCEPTED");
        seedDecisionLogWithConfidence(logId, "sig-trade", "TRDCO", "PACE_LIMIT");
        seedTradeRow(logId, "TRDCO");

        assertThat(repo.findHunterBrierPoints()).isEmpty();
    }

    /** P3 -- NUMERIC(4,3) -> BigDecimal.doubleValue() must land in the same bucket the old
     *  Double.parseDouble of the JSON string did: 0.700 is the LOWER edge of "0.7-0.8". */
    @Test
    void aConfidenceOnABucketEdgeLandsInTheSameBucketAsBefore() {
        seedSignalWithConfidence("sig-edge", "EDGECO", "SKIPPED", "0.700");
        seedLabelledCounterfactual("skip:sig-edge", "EDGECO", "LLM_SKIP");

        var results = calibration.hunterBrierResults(repo.findHunterBrierPoints());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().buckets())
                .extracting(CalibrationService.Bucket::range)
                .containsExactly("0.7-0.8");
    }
}
