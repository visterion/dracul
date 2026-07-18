package de.visterion.dracul.pattern;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate columns + evidence dedupe of migration V38, exercised against the real
 *  (testcontainer) DB. Covers the spec's migration test: V38 applies over the V1+V9
 *  seeds (context boot proves it) and the partial unique index permits many NULL
 *  outcome_ref rows while deduping non-null ones. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternGateRepositoryIT {

    private static final String GATE =
            "{\"conditions\":[{\"field\":\"mechanism\",\"op\":\"eq\",\"value\":\"PEAD\"}]}";

    @Autowired PatternRepository repo;
    @Autowired JdbcClient jdbc;

    private String insertGated(String status) {
        String statement = "Gated lesson " + UUID.randomUUID();
        repo.insertProposal("default", "strigoi-test", statement, 3, GATE);
        Pattern p = repo.findAllByUser("default").stream()
                .filter(x -> statement.equals(x.statement()))
                .findFirst().orElseThrow();
        if (!"PENDING".equals(status)) {
            repo.updateStatus(p.id(), "default", status);
        }
        return p.id();
    }

    @Test
    void insertProposalWithGatePersistsGateJson() {
        String id = insertGated("PENDING");
        Pattern p = repo.findById(id, "default").orElseThrow();
        assertThat(p.gateJson()).contains("\"mechanism\"");
        assertThat(p.gateJson()).contains("\"PEAD\"");
    }

    @Test
    void fourArgInsertProposalLeavesGateNull() {
        String statement = "Ungated lesson " + UUID.randomUUID();
        repo.insertProposal("default", "strigoi-test", statement, 1);
        Pattern p = repo.findAllByUser("default").stream()
                .filter(x -> statement.equals(x.statement()))
                .findFirst().orElseThrow();
        assertThat(p.gateJson()).isNull();
    }

    @Test
    void findEnforcedReturnsOnlyActiveGatedDefaultUserPatterns() {
        String activeGated = insertGated("ACTIVE");
        String pendingGated = insertGated("PENDING");
        String rejectedGated = insertGated("REJECTED");

        var enforced = repo.findEnforced();

        assertThat(enforced).extracting(EnforcedGate::id).contains(activeGated);
        assertThat(enforced).extracting(EnforcedGate::id)
                .doesNotContain(pendingGated, rejectedGated);
        assertThat(enforced).allSatisfy(g -> assertThat(g.gateJson()).isNotBlank());
    }

    @Test
    void findGatedForScoringReturnsPendingAndActiveButNotRejected() {
        String active = insertGated("ACTIVE");
        String pending = insertGated("PENDING");
        String rejected = insertGated("REJECTED");

        var gated = repo.findGatedForScoring();

        assertThat(gated).extracting(Pattern::id).contains(active, pending);
        assertThat(gated).extracting(Pattern::id).doesNotContain(rejected);
    }

    @Test
    void insertAutoEvidenceIsIdempotentPerOutcomeRef() {
        String id = insertGated("ACTIVE");
        String ref = "log-" + UUID.randomUUID();

        boolean first = repo.insertAutoEvidence(id, "ACME", "ACME", "PEAD",
                "2026-07-01T00:00:00Z", true, new BigDecimal("-4.20"), ref);
        boolean second = repo.insertAutoEvidence(id, "ACME", "ACME", "PEAD",
                "2026-07-01T00:00:00Z", true, new BigDecimal("-4.20"), ref);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        Integer rows = jdbc.sql("SELECT COUNT(*) FROM pattern_evidence WHERE outcome_ref = :ref")
                .param("ref", ref).query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void partialUniqueIndexPermitsManyNullOutcomeRefRows() {
        String id = insertGated("ACTIVE");
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type,
                                                  occurred_at, supported, return_percent, note)
                    VALUES (:id::uuid, 'SEED', 'Seed Co', 'PEAD', now(), true, 1.0, 'seed')
                    """).param("id", id).update();
        }
        Integer rows = jdbc.sql("SELECT COUNT(*) FROM pattern_evidence "
                        + "WHERE pattern_id = :id::uuid AND outcome_ref IS NULL")
                .param("id", id).query(Integer.class).single();
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void recomputeAggregatesUsesRuntimeRowsAndGreatestRule() {
        String id = insertGated("ACTIVE"); // evidence_count = 3 from insertProposal
        repo.insertAutoEvidence(id, "AAA", "AAA", "PEAD", "2026-07-01T00:00:00Z",
                true, new BigDecimal("-10.00"), "ref-" + UUID.randomUUID());
        repo.insertAutoEvidence(id, "BBB", "BBB", "PEAD", "2026-07-02T00:00:00Z",
                false, new BigDecimal("6.00"), "ref-" + UUID.randomUUID());

        repo.recomputeAggregates(id);

        Pattern p = repo.findById(id, "default").orElseThrow();
        assertThat(p.supportedCount()).isEqualTo(1);
        assertThat(p.avgUpliftPercent()).isEqualTo(-2.0);
        // GREATEST(existing 3, runtime 2) = 3 — the LLM's cited-symbol count survives.
        assertThat(p.evidenceCount()).isEqualTo(3);
    }

    @Test
    void recomputeAggregatesWithZeroRuntimeRowsLeavesPriorValuesUntouched() {
        String id = insertGated("ACTIVE");
        jdbc.sql("UPDATE patterns SET supported_count = 9, avg_uplift_percent = 12.5 "
                        + "WHERE id = :id::uuid").param("id", id).update();

        repo.recomputeAggregates(id);

        Pattern p = repo.findById(id, "default").orElseThrow();
        assertThat(p.supportedCount()).isEqualTo(9);
        assertThat(p.avgUpliftPercent()).isEqualTo(12.5);
        assertThat(p.evidenceCount()).isEqualTo(3);
    }

    @Test
    void replaceGateDeletesAutoEvidenceKeepsSeedRowsAndClearsAggregates() {
        String id = insertGated("ACTIVE");
        repo.insertAutoEvidence(id, "AAA", "AAA", "PEAD", "2026-07-01T00:00:00Z",
                true, new BigDecimal("-10.00"), "ref-" + UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type,
                                              occurred_at, supported, return_percent, note)
                VALUES (:id::uuid, 'SEED', 'Seed Co', 'PEAD', now(), true, 1.0, 'seed')
                """).param("id", id).update();
        repo.recomputeAggregates(id);

        int updated = repo.replaceGate(id, "default",
                "{\"conditions\":[{\"field\":\"price\",\"op\":\"lt\",\"value\":5}]}");

        assertThat(updated).isEqualTo(1);
        Integer autoRows = jdbc.sql("SELECT COUNT(*) FROM pattern_evidence "
                        + "WHERE pattern_id = :id::uuid AND outcome_ref IS NOT NULL")
                .param("id", id).query(Integer.class).single();
        Integer seedRows = jdbc.sql("SELECT COUNT(*) FROM pattern_evidence "
                        + "WHERE pattern_id = :id::uuid AND outcome_ref IS NULL")
                .param("id", id).query(Integer.class).single();
        assertThat(autoRows).isZero();
        assertThat(seedRows).isEqualTo(1);
        Pattern p = repo.findById(id, "default").orElseThrow();
        assertThat(p.gateJson()).contains("\"price\"");
        // Auto-evidence existed and was deleted -> the runtime-derived aggregates are
        // cleared to NULL ("no evidence yet"), not left describing deleted rows.
        assertThat(p.supportedCount()).isNull();
        assertThat(p.avgUpliftPercent()).isNull();
        assertThat(p.evidenceCount()).isEqualTo(3);
    }

    @Test
    void replaceGateOnSeedOnlyPatternLeavesAggregatesUntouched() {
        String id = insertGated("ACTIVE");
        jdbc.sql("UPDATE patterns SET supported_count = 9, avg_uplift_percent = 12.5 "
                        + "WHERE id = :id::uuid").param("id", id).update();

        repo.replaceGate(id, "default", null); // explicit clear, no auto-evidence existed

        Pattern p = repo.findById(id, "default").orElseThrow();
        assertThat(p.gateJson()).isNull();
        assertThat(p.supportedCount()).isEqualTo(9);
        assertThat(p.avgUpliftPercent()).isEqualTo(12.5);
    }

    @Test
    void replaceGateReturnsZeroForUnknownPattern() {
        int updated = repo.replaceGate(UUID.randomUUID().toString(), "default", null);
        assertThat(updated).isZero();
    }

    @Test
    void blockedCountsParsesPatternIdFromVetoResultsAndCountsDistinctSignals() {
        String gateId = insertGated("ACTIVE");
        String otherId = insertGated("ACTIVE");
        // Two rejects for the SAME signal on gateId (transient re-attempts) -> counts as 1.
        insertDecisionLog("sig-A", "PATTERN_GATE", gateId, false);
        insertDecisionLog("sig-A", "PATTERN_GATE", gateId, false);
        insertDecisionLog("sig-B", "PATTERN_GATE", gateId, false);
        // Rejected COOLDOWN whose trace ALSO contains a gate FAIL -> reason_code filter
        // excludes it (spec D6 precision rule).
        insertDecisionLog("sig-C", "COOLDOWN", gateId, false);
        // PATTERN_GATE reject attributed to the OTHER gate -> not counted for gateId.
        insertDecisionLog("sig-D", "PATTERN_GATE", otherId, false);

        var counts = repo.blockedCounts();

        assertThat(counts.get(gateId)).isEqualTo(2L);
        assertThat(counts.get(otherId)).isEqualTo(1L);
    }

    private void insertDecisionLog(String signalId, String reasonCode, String patternId,
            boolean passed) {
        String vetoResults = "[{\"check\":\"SCHEMA_INVALID\",\"passed\":true,\"measured\":\"ok\"},"
                + "{\"check\":\"PATTERN_GATE\",\"passed\":" + passed + ",\"measured\":\"pattern_gate:"
                + patternId + " (test-gate)\"}]";
        jdbc.sql("""
                INSERT INTO decision_log (log_id, rule_version, trigger_type, signal_id, symbol,
                                          veto_results, action, reason_code)
                VALUES (gen_random_uuid(), 'exec-test', 'SIGNAL', :signalId, 'ACME',
                        CAST(:vetoResults AS jsonb), 'REJECT', :reasonCode)
                """)
                .param("signalId", signalId)
                .param("vetoResults", vetoResults)
                .param("reasonCode", reasonCode)
                .update();
    }
}
