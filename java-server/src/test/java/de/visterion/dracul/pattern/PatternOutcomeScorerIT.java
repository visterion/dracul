package de.visterion.dracul.pattern;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PatternOutcomeScorer against the real (testcontainer) DB (spec T3.3 D5). Fixture rows
 *  are inserted directly into executor_signal / executor_position / outcome_log; every
 *  symbol is unique per test so container reuse and seed churn cannot interfere. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternOutcomeScorerIT {

    @Autowired PatternOutcomeScorer scorer;
    @Autowired PatternRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanExecutorFixtures() {
        // Full-rescan job: leftover fixture outcomes from other test methods are fine
        // (unique symbols/gates), but wipe rows older test RUNS left behind for stability.
        jdbc.sql("DELETE FROM outcome_log WHERE symbol LIKE 'SCR%'").update();
        jdbc.sql("DELETE FROM executor_position WHERE symbol LIKE 'SCR%'").update();
        jdbc.sql("DELETE FROM executor_signal WHERE symbol LIKE 'SCR%'").update();
    }

    private String gatedPattern(String status, String gateJson) {
        String statement = "Scorer lesson " + UUID.randomUUID();
        repo.insertProposal("default", "strigoi-test", statement, 3, gateJson);
        Pattern p = repo.findAllByUser("default").stream()
                .filter(x -> statement.equals(x.statement()))
                .findFirst().orElseThrow();
        if (!"PENDING".equals(status)) repo.updateStatus(p.id(), "default", status);
        return p.id();
    }

    private String mechGate(String mechanism) {
        return "{\"conditions\":[{\"field\":\"mechanism\",\"op\":\"eq\",\"value\":\""
                + mechanism + "\"}]}";
    }

    /** Inserts signal + closed position + complete TRADE outcome; returns log_id_ref. */
    private String tradeOutcome(String symbol, String mechanism, String sector,
            String entryPrice, String initialStop, String realizedR, boolean complete,
            String closedAt) {
        String signalId = "scr-sig-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO executor_signal (signal_id, source, symbol, direction, confidence,
                                             mechanism, status)
                VALUES (:id, 'strigoi-test', :symbol, 'BUY', 0.8, :mechanism, 'EXECUTED')
                """)
                .param("id", signalId).param("symbol", symbol).param("mechanism", mechanism)
                .update();
        Long positionId = jdbc.sql("""
                INSERT INTO executor_position (connection, symbol, side, qty, entry_price,
                                               initial_stop, active_stop, source_signal_id,
                                               status, sector, closed_at, realized_r)
                VALUES ('sim', :symbol, 'BUY', 10, :entryPrice::numeric, :initialStop::numeric,
                        :initialStop::numeric, :signalId, 'CLOSED', :sector,
                        CASE WHEN :closedAt::timestamptz IS NULL THEN NULL
                             ELSE :closedAt::timestamptz END,
                        :realizedR::numeric)
                RETURNING id
                """)
                .param("symbol", symbol).param("entryPrice", entryPrice)
                .param("initialStop", initialStop).param("signalId", signalId)
                .param("sector", sector).param("closedAt", closedAt)
                .param("realizedR", realizedR)
                .query(Long.class).single();
        String logIdRef = "scr-log-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outcome_log (kind, log_id_ref, position_id, symbol, realized_r,
                                         complete)
                VALUES ('TRADE', :ref, :positionId, :symbol, :realizedR::numeric, :complete)
                """)
                .param("ref", logIdRef).param("positionId", positionId)
                .param("symbol", symbol).param("realizedR", realizedR)
                .param("complete", complete)
                .update();
        return logIdRef;
    }

    private List<PatternCase> cases(String patternId) {
        return repo.findCases(patternId, "default");
    }

    @Test
    void matchingOutcomeProducesEvidenceAndAggregates() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_1"));
        // realized_r = -1, entry 10, stop 9 -> r_per_share 1 -> return_percent = -10.00
        // (the same |entry - initial_stop| r_per_share basis OutcomeBatchJob.computeR uses).
        tradeOutcome("SCRAAA", "SCR_PEAD_1", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();

        var evidence = cases(patternId);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).symbol()).isEqualTo("SCRAAA");
        assertThat(evidence.get(0).companyName()).isEqualTo("SCRAAA");
        assertThat(evidence.get(0).anomalyType()).isEqualTo("SCR_PEAD_1");
        assertThat(evidence.get(0).supported()).isTrue(); // losing trade supports the warning
        assertThat(evidence.get(0).returnPercent()).isEqualTo(-10.0);
        assertThat(evidence.get(0).note()).isEqualTo("scored:auto");

        Pattern p = repo.findById(patternId, "default").orElseThrow();
        assertThat(p.supportedCount()).isEqualTo(1);
        assertThat(p.avgUpliftPercent()).isEqualTo(-10.0);
        assertThat(p.evidenceCount()).isEqualTo(3); // GREATEST(3 seed, 1 runtime)
    }

    @Test
    void rerunIsIdempotent() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_2"));
        tradeOutcome("SCRBBB", "SCR_PEAD_2", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();
        scorer.score();

        assertThat(cases(patternId)).hasSize(1);
    }

    @Test
    void priceGateMatchesOnEntryPriceBasis() {
        String patternId = gatedPattern("ACTIVE",
                "{\"conditions\":[{\"field\":\"price\",\"op\":\"lt\",\"value\":5}]}");
        tradeOutcome("SCRCHP", "SCR_ANY", "Tech", "4.50", "4.00", "-1", true,
                "2026-07-01T00:00:00Z"); // entry 4.50 < 5 -> matches
        tradeOutcome("SCREXP", "SCR_ANY", "Tech", "50", "45", "-1", true,
                "2026-07-01T00:00:00Z"); // entry 50 -> no match

        scorer.score();

        var evidence = cases(patternId);
        assertThat(evidence).extracting(PatternCase::symbol).containsExactly("SCRCHP");
    }

    @Test
    void incompleteOutcomeExcluded() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_3"));
        tradeOutcome("SCRCCC", "SCR_PEAD_3", "Tech", "10", "9", "-1", false,
                "2026-07-01T00:00:00Z");

        scorer.score();

        assertThat(cases(patternId)).isEmpty();
    }

    @Test
    void nullRealizedRIsDefinedSkipNotError() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_4"));
        tradeOutcome("SCRDDD", "SCR_PEAD_4", "Tech", "10", "9", null, true,
                "2026-07-01T00:00:00Z");
        tradeOutcome("SCREEE", "SCR_PEAD_4", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score(); // must not throw; the null row is skipped, the good one scored

        assertThat(cases(patternId)).extracting(PatternCase::symbol).containsExactly("SCREEE");
    }

    @Test
    void nullClosedAtFallsBackToComputedAtForOccurredAt() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_5"));
        tradeOutcome("SCRFFF", "SCR_PEAD_5", "Tech", "10", "9", "-1", true, null);

        scorer.score();

        var evidence = cases(patternId);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).occurredAt()).isNotNull(); // computed_at fallback used
    }

    @Test
    void gatelessAndRejectedPatternsAreSkipped() {
        String gateless = gatedPattern("ACTIVE", mechGate("SCR_PEAD_6"));
        repo.replaceGate(gateless, "default", null); // now advisory-only
        String rejected = gatedPattern("REJECTED", mechGate("SCR_PEAD_6"));
        tradeOutcome("SCRGGG", "SCR_PEAD_6", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();

        assertThat(cases(gateless)).isEmpty();
        assertThat(cases(rejected)).isEmpty();
    }

    @Test
    void pendingGatedPatternGetsEvidenceBeforeApproval() {
        String pending = gatedPattern("PENDING", mechGate("SCR_PEAD_7"));
        tradeOutcome("SCRHHH", "SCR_PEAD_7", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();

        assertThat(cases(pending)).hasSize(1);
    }

    @Test
    void seedRowsSurviveAndStayOutOfAggregates() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_8"));
        jdbc.sql("""
                INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type,
                                              occurred_at, supported, return_percent, note)
                VALUES (:id::uuid, 'SEEDX', 'Seed Co', 'PEAD', now(), false, 99.0, 'seed')
                """).param("id", patternId).update();
        tradeOutcome("SCRIII", "SCR_PEAD_8", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();

        Pattern p = repo.findById(patternId, "default").orElseThrow();
        // Aggregates over runtime rows only: seed's +99% must not enter the average.
        assertThat(p.avgUpliftPercent()).isEqualTo(-10.0);
        assertThat(p.supportedCount()).isEqualTo(1);
        assertThat(cases(patternId)).hasSize(2); // seed row survives
    }

    @Test
    void malformedStoredGateOnOnePatternDoesNotPoisonOthers() {
        String broken = gatedPattern("ACTIVE", mechGate("SCR_PEAD_9"));
        jdbc.sql("UPDATE patterns SET gate = '\"not-an-object\"'::jsonb WHERE id = :id::uuid")
                .param("id", broken).update();
        String healthy = gatedPattern("ACTIVE", mechGate("SCR_PEAD_9"));
        tradeOutcome("SCRJJJ", "SCR_PEAD_9", "Tech", "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score(); // must not throw

        assertThat(cases(healthy)).hasSize(1);
        assertThat(cases(broken)).isEmpty();
    }

    @Test
    void poisonOutcomeIsIsolatedAndOthersStillScore() {
        String patternId = gatedPattern("ACTIVE", mechGate("SCR_PEAD_11"));
        // Poison via NUMERIC overflow: entry 1, stop 1001 -> r_per_share 1000 ->
        // return_percent = -2 x (1000 / 1) x 100 = -200000.00, which exceeds the
        // NUMERIC(7,2) range of pattern_evidence.return_percent (V9, max +/-99999.99).
        // The scorer computes it unclamped (setScale only fixes the scale), so the
        // insertAutoEvidence INSERT throws "numeric field overflow" inside the
        // per-outcome try/catch (ERROR log) and the run continues; score() runs without
        // a wrapping transaction, so the healthy outcome's insert commits independently.
        tradeOutcome("SCRPOI", "SCR_PEAD_11", "Tech", "1", "1001", "-2", true,
                "2026-07-01T00:00:00Z");
        tradeOutcome("SCRNNN", "SCR_PEAD_11", "Tech", "10", "9", "-1", true,
                "2026-07-02T00:00:00Z");

        scorer.score(); // must not throw

        // Only the healthy outcome produced an evidence row.
        assertThat(cases(patternId)).extracting(PatternCase::symbol).containsExactly("SCRNNN");
    }

    @Test
    void nullSectorOnPositionFailsOpenForSectorGatesButOtherConditionsStillScore() {
        String sectorGate = gatedPattern("ACTIVE",
                "{\"conditions\":[{\"field\":\"sector\",\"op\":\"eq\",\"value\":\"Tech\"}]}");
        String mechOnly = gatedPattern("ACTIVE", mechGate("SCR_PEAD_10"));
        // sector NULL on the position; SectorCascade has no Agora in tests -> resolves null.
        tradeOutcome("SCRKKK", "SCR_PEAD_10", null, "10", "9", "-1", true,
                "2026-07-01T00:00:00Z");

        scorer.score();

        assertThat(cases(sectorGate)).isEmpty();   // fail-open, no evidence
        assertThat(cases(mechOnly)).hasSize(1);    // mechanism condition still evaluable
    }
}
