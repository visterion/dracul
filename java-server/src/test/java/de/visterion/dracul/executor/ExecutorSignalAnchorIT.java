package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins V50's reference_source column: its CHECK constraint, its backfill UPDATEs, and the
 *  ExecutorSignalRepository finders/writers SP12's nightly anchor-reconstruction step uses. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorSignalAnchorIT {

    @Autowired ExecutorSignalRepository repo;
    @Autowired ExecutorDecisionRepository decisions;
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

    private void seedSignal(String id, String symbol, String status, String createdAt,
                            String price, String barDate, String atr, String source) {
        jdbc.sql("""
                INSERT INTO executor_signal
                  (signal_id, source, agent_version, symbol, direction, confidence, mechanism,
                   horizon, reference_price, status, created_at, reference_bar_date, reference_atr,
                   reference_source)
                VALUES (:id, 'strigoi-echo', 'v1', :sym, 'BUY', 0.6, 'NEWS', '3m',
                        CAST(:price AS numeric), :status, CAST(:created AS timestamptz),
                        CAST(:bar AS date), CAST(:atr AS numeric), :src)
                """)
                .param("id", id).param("sym", symbol).param("status", status)
                .param("created", createdAt).param("price", price)
                .param("bar", barDate).param("atr", atr).param("src", source)
                .update();
    }

    private void seedDecision(String signalId, String symbol, String action, String rejectReason) {
        decisions.insert(new ExecutorDecision(null, signalId, symbol, false, rejectReason, List.of(),
                "rationale", null, "run-1", null, action));
    }

    /** Same technique as ExecutorDecisionRepositoryIT's "(a) vetoed" case: a decision_log REJECT
     *  partner excludes a signal from both counterfactual loops, and from findAnchorCandidates. */
    private void seedRejectDecisionLog(String signalId, String symbol) {
        decisionLog.insert(new DecisionLog(null, "run-1", "exec-v0.6", "SIGNAL", signalId,
                "strigoi-echo", "v1", symbol, null, null, "REJECT", "MAX_POSITIONS",
                null, null, null, null, null));
    }

    @Test
    void candidatesAreAnchorlessSkipsAndSweeps() {
        seedSignal("c-skip", "TESTCO", "SKIPPED", "2026-08-04 04:01:00+00", "10.5", null, null, null);
        seedDecision("c-skip", "TESTCO", "SKIP", null);
        seedSignal("c-sweep", "OTHERCO", "EXPIRED", "2026-08-05 04:01:00+00", "20", null, null, null);
        seedDecision("c-sweep", "OTHERCO", PendingSignalSweeper.ACTION, "SIGNAL_EXPIRED");
        seedSignal("c-half", "HALFCO", "SKIPPED", "2026-08-06 04:01:00+00", "30", null, "1.5", null);
        seedDecision("c-half", "HALFCO", "SKIP", null);
        // excluded:
        seedSignal("x-anch", "ANCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", "2026-08-03", "1.2", "emission");
        seedDecision("x-anch", "ANCO", "SKIP", null);
        seedSignal("x-noprice", "NPCO", "SKIPPED", "2026-08-04 04:01:00+00", null, null, null, null);
        seedDecision("x-noprice", "NPCO", "SKIP", null);
        seedSignal("x-accepted", "ACCO", "ACCEPTED", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedDecision("x-accepted", "ACCO", "SKIP", null);
        seedSignal("x-marked", "MKCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, "unreconstructable");
        seedDecision("x-marked", "MKCO", "SKIP", null);
        seedSignal("x-reject", "RJCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedDecision("x-reject", "RJCO", "SKIP", null);
        // a SIGNAL/REJECT decision_log partner excludes it (same rule as findSkipsWithoutDecisionLog):
        seedRejectDecisionLog("x-reject", "RJCO");
        seedSignal("x-hold", "HDCO", "PENDING", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedDecision("x-hold", "HDCO", "HOLD", null);

        assertThat(repo.findAnchorCandidates(100)).extracting(AnchorCandidate::signalId)
                .containsExactly("c-skip", "c-sweep", "c-half");     // oldest emission first
    }

    @Test
    void candidateCarriesEmissionInstantNotDecisionTime() {
        seedSignal("c1", "TESTCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedDecision("c1", "TESTCO", "SKIP", null);            // decision created_at = now()
        assertThat(repo.findAnchorCandidates(10).getFirst().emittedAt())
                .isEqualTo(Instant.parse("2026-08-04T04:01:00Z"));
    }

    @Test
    void emittedAtIsIndependentOfJvmDefaultZone() {
        TimeZone prev = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            seedSignal("c1", "TESTCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
            seedDecision("c1", "TESTCO", "SKIP", null);
            assertThat(repo.findAnchorCandidates(10).getFirst().emittedAt())
                    .isEqualTo(Instant.parse("2026-08-04T04:01:00Z"));
        } finally { TimeZone.setDefault(prev); }
    }

    @Test
    void limitIsApplied() {
        seedSignal("c-1", "AAACO", "SKIPPED", "2026-08-01 04:01:00+00", "10", null, null, null);
        seedDecision("c-1", "AAACO", "SKIP", null);
        seedSignal("c-2", "BBBCO", "SKIPPED", "2026-08-02 04:01:00+00", "10", null, null, null);
        seedDecision("c-2", "BBBCO", "SKIP", null);
        seedSignal("c-3", "CCCCO", "SKIPPED", "2026-08-03 04:01:00+00", "10", null, null, null);
        seedDecision("c-3", "CCCCO", "SKIP", null);

        assertThat(repo.findAnchorCandidates(2)).extracting(AnchorCandidate::signalId)
                .containsExactly("c-1", "c-2");
    }

    @Test
    void twoDecisionRowsForOneSignalYieldOneCandidate() {
        seedSignal("c-dup", "DUPCO", "EXPIRED", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedDecision("c-dup", "DUPCO", "SKIP", null);
        seedDecision("c-dup", "DUPCO", PendingSignalSweeper.ACTION, "SIGNAL_EXPIRED");

        assertThat(repo.findAnchorCandidates(100)).extracting(AnchorCandidate::signalId)
                .containsExactly("c-dup");
    }

    @Test
    void writeReconstructedNeverOverwritesEmission() {
        seedSignal("e1", "ANCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", "2026-08-03", "1.2000", "emission");
        assertThat(repo.writeReconstructedAnchor("e1", LocalDate.parse("2026-08-01"), new BigDecimal("9.9"))).isZero();
        assertThat(repo.findReferenceSource("e1")).isEqualTo("emission");
    }

    @Test
    void writeReconstructedFillsAnchorlessAndHalfAnchored() {
        seedSignal("a1", "TESTCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
        seedSignal("h1", "HALFCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, "7.7", null);
        assertThat(repo.writeReconstructedAnchor("a1", LocalDate.parse("2026-08-03"), new BigDecimal("1.2345"))).isEqualTo(1);
        assertThat(repo.writeReconstructedAnchor("h1", LocalDate.parse("2026-08-03"), new BigDecimal("2.0000"))).isEqualTo(1);
        // both rows: bar 2026-08-03, atr as written (h1's 7.7 replaced), source 'reconstructed'
        assertThat(repo.findReferenceSource("a1")).isEqualTo("reconstructed");
        assertThat(repo.findReferenceSource("h1")).isEqualTo("reconstructed");
    }

    @Test
    void markUnreconstructableOnlyWhenUnset() {
        seedSignal("u1", "UCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
        assertThat(repo.markUnreconstructable("u1")).isEqualTo(1);
        assertThat(repo.findReferenceSource("u1")).isEqualTo("unreconstructable");

        seedSignal("u2", "UCO2", "SKIPPED", "2026-08-04 04:01:00+00", "10", "2026-08-03", "1.2", "emission");
        assertThat(repo.markUnreconstructable("u2")).isZero();
        assertThat(repo.findReferenceSource("u2")).isEqualTo("emission");
    }

    @Test
    void insertDerivesReferenceSource() {
        repo.insert(new ExecutorSignal("ins-1", "strigoi-echo", "v1", "INSCO", "BUY", 0.6,
                "NEWS", List.of(), "3m", new BigDecimal("10"), "SKIPPED", null,
                null, null, LocalDate.parse("2026-08-03"), new BigDecimal("1.2")));
        assertThat(repo.findReferenceSource("ins-1")).isEqualTo("emission");

        repo.insert(new ExecutorSignal("ins-2", "strigoi-echo", "v1", "INSCO2", "BUY", 0.6,
                "NEWS", List.of(), "3m", new BigDecimal("10"), "SKIPPED", null,
                null, null, null, null));
        assertThat(repo.findReferenceSource("ins-2")).isNull();

        repo.insert(new ExecutorSignal("ins-3", "strigoi-echo", "v1", "INSCO3", "BUY", 0.6,
                "NEWS", List.of(), "3m", new BigDecimal("10"), "SKIPPED", null,
                null, null, LocalDate.parse("2026-08-03"), null));
        assertThat(repo.findReferenceSource("ins-3")).isNull();
    }

    @Test
    void shadowSampleIsNewestEmissionRowsAfterCutoff() {
        seedSignal("sh-1", "SHCO1", "SKIPPED", "2026-09-16 04:01:00+00", "10", "2026-09-15", "1.1", "emission");
        seedSignal("sh-2", "SHCO2", "SKIPPED", "2026-09-18 04:01:00+00", "10", "2026-09-17", "1.2", "emission");
        seedSignal("sh-3", "SHCO3", "SKIPPED", "2026-09-19 04:01:00+00", "10", "2026-09-18", "1.3", "emission");
        seedSignal("sh-4", "SHCO4", "SKIPPED", "2026-09-20 04:01:00+00", "10", "2026-09-19", "1.4", "reconstructed");

        List<AnchorShadowRow> sample = repo.findShadowSample(2, Instant.parse("2026-09-17T04:00:00Z"));

        assertThat(sample).extracting(AnchorShadowRow::signalId).containsExactly("sh-3", "sh-2");
        assertThat(sample.getFirst().storedBarDate()).isEqualTo(LocalDate.parse("2026-09-18"));
        assertThat(sample.getFirst().storedAtr()).isEqualByComparingTo("1.3");
        assertThat(sample.get(1).storedBarDate()).isEqualTo(LocalDate.parse("2026-09-17"));
        assertThat(sample.get(1).storedAtr()).isEqualByComparingTo("1.2");
    }

    /** V50 has already run in this container's schema; re-execute its two UPDATE statements
     *  verbatim (read from the migration file) to pin their SQL effect against seeded rows. */
    @Test
    void v50BackfillClassifiesEmissionVsManual() throws IOException {
        seedSignal("bf-emission", "BFCO1", "SKIPPED", "2026-08-04 04:01:00+00", "10", "2026-08-03", "1.2", null);
        seedSignal("bf-manual", "BFCO2", "SKIPPED", "2026-08-28 04:01:00+00", "10", "2026-09-08", "1.2", null);

        String sql = new String(Files.readAllBytes(
                new ClassPathResource("db/migration/V50__executor_signal_reference_source.sql").getFile().toPath()),
                StandardCharsets.UTF_8);
        String backfill = sql.substring(sql.indexOf("UPDATE executor_signal SET reference_source = 'emission'"));
        for (String statement : backfill.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.sql(trimmed).update();
            }
        }

        assertThat(repo.findReferenceSource("bf-emission")).isEqualTo("emission");
        assertThat(repo.findReferenceSource("bf-manual")).isEqualTo("manual");
    }

    @Test
    void checkConstraintRejectsUnknownSource() {
        seedSignal("bad-1", "BADCO", "SKIPPED", "2026-08-04 04:01:00+00", "10", null, null, null);
        assertThatThrownBy(() -> jdbc.sql(
                "UPDATE executor_signal SET reference_source = 'bogus' WHERE signal_id = :id")
                .param("id", "bad-1")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
