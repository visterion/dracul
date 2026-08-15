package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Regression anchor for the spin hunter's tool-result size — mirrors {@code MergerPayloadBudgetTest}
 * (the merger hunter's own version of this exact failure, {@code status=done} /
 * {@code {"prey": []}} with nothing in the log to say why).
 *
 * <p><b>Why this fix exists.</b> The spin hunter now reads the EX-99.1 information statement (a
 * ~200-page document) instead of the short Form-10 shell, so {@code term_sheet_text} fills
 * Agora's full 24 000-char {@code get_filing_text} window per row, where before it was a 10-12 kB
 * cover document. {@link SpinCandidateEnricher#toWire} used to ship {@code row.termSheetText()}
 * raw; nine current prod rows x 24 000 = 216 000 chars, more than double the 100 000-char hard
 * ceiling documented in {@code docs/wie-dracul-entscheidet.md} ("Das Tool-Result-Limit der
 * Bridge") — and this is not hypothetical, it is the exact class of failure that already blinded
 * the merger hunter on production run {@code 74754073...} (2026-08-04).
 *
 * <p><b>Why this targets the HARD ceiling (100 000), not the safe zone (50 000) — unlike the
 * merger fix.</b> {@link EnrichedSpinCandidate} carries far more structured fields than the merger
 * candidate (lifecycle status, three stage-gated snapshot blocks). {@link
 * #measureStructuredFieldsAloneAtFullResponseLimit} measures {@link
 * SpinCandidateEnricher#RESPONSE_LIMIT} rows of structured fields ALONE — {@code termSheet} null —
 * at ~48 150 chars, already within a few percent of the 50 000-char safe zone before a single
 * term-sheet character is added. Fitting the whole response under the safe zone at full
 * {@link SpinCandidateEnricher#RESPONSE_LIMIT} saturation is therefore not achievable without also
 * shrinking that row cap, which is out of scope here (the D11 window fix already calibrated it).
 * This fix instead guarantees the payload stays clear of the 100 000-char HARD ceiling — the
 * number that actually causes the Claude-Max bridge to cut an MCP tool result mid-JSON — with a
 * real margin, which is what stops the C-1 failure.
 *
 * <p><b>The arithmetic.</b>
 * <pre>
 *   structured fields, RESPONSE_LIMIT (50) rows, termSheet=null (measured)   ~48 150 chars
 *   + term-sheet prose budget (TERM_SHEET_BUDGET_TOTAL_CHARS)                  45 000 chars
 *   + envelope (data_source_health + active_patterns, uncapped)                5 000 chars
 *   = worst case                                                             ~98 150 chars
 * </pre>
 * ~98 150 is under the 100 000-char hard ceiling with real margin, and a 7x-plus reduction from
 * the 216 000-1 200 000 chars the unbudgeted payload would have shipped. Note the term-sheet
 * budget is spent ONLY on rows that still {@link SpinCandidateEnricher#needsReading need a
 * reading} — a row with agent-verified dates gets {@code null} prose — so the worst case modelled
 * here (every row needing a reading) is deliberately the most pessimistic shape, not the typical
 * one; in the current nine-row production set the term-sheet total is a small fraction of the
 * budget.
 */
class SpinPayloadBudgetTest {

    /** Same hard ceiling {@code MergerPayloadBudgetTest} pins: the Claude Code CLI's MCP
     *  truncation cuts a tool result to {@code MAX_MCP_OUTPUT_TOKENS * 4} chars above 25 000
     *  tokens. Above this, the model receives a syntactically broken candidate list. */
    private static final int HARD_CEILING_CHARS = 100_000;

    /** Reserve for {@code data_source_health} + {@code active_patterns} (uncapped) riding the
     *  same tool envelope — mirrors {@code MergerPayloadBudgetTest.ENVELOPE_RESERVE_CHARS}. */
    private static final int ENVELOPE_RESERVE_CHARS = 5_000;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ObjectMapper rowMapper = new ObjectMapper();

    /**
     * Measures {@link SpinCandidateEnricher#RESPONSE_LIMIT} rows of {@link EnrichedSpinCandidate}
     * structured fields ALONE (term sheet null) — the number the class javadoc above depends on.
     * Field values are the longest realistic ones (longest company name / EDGAR archive URL /
     * ratio phrase the parser's regex can produce), mirroring how {@code MergerPayloadBudgetTest}
     * built its worst-case structured block.
     */
    @Test
    void measureStructuredFieldsAloneAtFullResponseLimit() {
        List<EnrichedSpinCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < SpinCandidateEnricher.RESPONSE_LIMIT; i++) {
            candidates.add(worstCaseStructuredCandidate(null));
        }
        String json = mapper.writeValueAsString(candidates);
        assertThat(json.length())
                .as("structured-only payload for %d rows is %d chars",
                        SpinCandidateEnricher.RESPONSE_LIMIT, json.length())
                .isLessThan(HARD_CEILING_CHARS - TermSheetAndEnvelope());
    }

    /**
     * The point of this fix: {@link SpinCandidateEnricher#payload} run through a mocked repo
     * returning {@link SpinCandidateEnricher#RESPONSE_LIMIT} rows, EVERY one carrying a full
     * 24 000-char term sheet and no verified dates (the worst case — every row still needs a
     * reading), stays under the hard ceiling with margin.
     */
    @Test
    void worstCasePayloadStaysUnderTheHardCeiling() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        List<SpinCandidateRow> rows = new ArrayList<>();
        for (int i = 0; i < SpinCandidateEnricher.RESPONSE_LIMIT; i++) {
            rows.add(worstCaseRow(i));
        }
        when(repo.findActiveUnpromotedInWindow(any(), anyInt())).thenReturn(rows);

        SpinCandidateEnricher enricher = new SpinCandidateEnricher(repo,
                Mockito.mock(SpinLifecycleReconciler.class),
                Mockito.mock(SpinBalanceSheetSnapshotter.class),
                Mockito.mock(SpinDistributionSnapshotter.class),
                Mockito.mock(SpinValuationSnapshotter.class),
                Mockito.mock(de.visterion.dracul.hunting.agora.AgoraFilings.class),
                Mockito.mock(SpinTermsParser.class),
                rowMapper);

        SpinPayload payload = enricher.payload(LocalDate.parse("2026-08-15"));
        String json = mapper.writeValueAsString(payload.candidates());
        int worstCase = json.length() + ENVELOPE_RESERVE_CHARS;

        assertThat(worstCase)
                .as("%d rows, every one needing a reading with a 24 000-char term sheet, produce a "
                                + "%d-char payload (+%d envelope reserve) = %d; the hard ceiling is %d. "
                                + "Above the ceiling the Claude-Max bridge cuts the tool result "
                                + "mid-JSON and the model answers {\"prey\": []} with status=done "
                                + "and nothing in the log to explain why (this already happened once, "
                                + "on the merger hunter, 2026-08-04).",
                        SpinCandidateEnricher.RESPONSE_LIMIT, json.length(), ENVELOPE_RESERVE_CHARS,
                        worstCase, HARD_CEILING_CHARS)
                .isLessThan(HARD_CEILING_CHARS);

        // Nothing may put the raw 24 000-char term sheet back on the wire — that is the whole
        // regression. Every needing-reading row's term sheet must be capped well under the raw size.
        for (EnrichedSpinCandidate c : payload.candidates()) {
            assertThat(c.termSheet()).isNotNull();
            assertThat(c.termSheet().length())
                    .as("candidate %d term sheet is %d chars — the per-row cap is %d", c.id(),
                            c.termSheet().length(), SpinCandidateEnricher.TERM_SHEET_PER_ROW_CAP_CHARS)
                    .isLessThanOrEqualTo(SpinCandidateEnricher.TERM_SHEET_PER_ROW_CAP_CHARS);
        }
    }

    /** The share-per-row x needy-row arithmetic never spends more than the total budget, whatever
     *  the row count — checked in the abstract, mirroring
     *  {@code MergerPayloadBudgetTest.capAndDigestSizeSatisfyTheDerivation}. */
    @Test
    void perRowShareTimesResponseLimitNeverExceedsTheTotalBudget() {
        int needy = SpinCandidateEnricher.RESPONSE_LIMIT;
        int share = Math.min(SpinCandidateEnricher.TERM_SHEET_PER_ROW_CAP_CHARS,
                SpinCandidateEnricher.TERM_SHEET_BUDGET_TOTAL_CHARS / needy);

        assertThat((long) share * needy)
                .as("share %d x needy rows %d must not exceed the %d total budget",
                        share, needy, SpinCandidateEnricher.TERM_SHEET_BUDGET_TOTAL_CHARS)
                .isLessThanOrEqualTo(SpinCandidateEnricher.TERM_SHEET_BUDGET_TOTAL_CHARS);
    }

    /** A row whose dates are already agent-verified (D5) must not have its prose re-sent — this is
     *  what keeps the budget comfortable instead of starved when only a few rows still need a
     *  reading. */
    @Test
    void aRowWithVerifiedDatesGetsNoTermSheetProse() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        SpinCandidateRow verified = new SpinCandidateRow(1L, "cik1", "SYM1", "Co 1", "10-12B",
                LocalDate.parse("2026-07-01"), "https://sec/1", "one for four",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10"), true,
                "x".repeat(24_000), null, SpinStatus.DISTRIBUTED, null, null, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z",
                "2026-08-01T00:00:00Z", null, null, null);
        when(repo.findActiveUnpromotedInWindow(any(), anyInt())).thenReturn(List.of(verified));

        SpinCandidateEnricher enricher = new SpinCandidateEnricher(repo,
                Mockito.mock(SpinLifecycleReconciler.class),
                Mockito.mock(SpinBalanceSheetSnapshotter.class),
                Mockito.mock(SpinDistributionSnapshotter.class),
                Mockito.mock(SpinValuationSnapshotter.class),
                Mockito.mock(de.visterion.dracul.hunting.agora.AgoraFilings.class),
                Mockito.mock(SpinTermsParser.class),
                rowMapper);

        SpinPayload payload = enricher.payload(LocalDate.parse("2026-07-01"));

        assertThat(payload.candidates()).hasSize(1);
        assertThat(payload.candidates().getFirst().termSheet()).isNull();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static int TermSheetAndEnvelope() {
        return SpinCandidateEnricher.TERM_SHEET_BUDGET_TOTAL_CHARS + ENVELOPE_RESERVE_CHARS;
    }

    private SpinCandidateRow worstCaseRow(int i) {
        return new SpinCandidateRow(i, "cik" + i, "ABCDE", worstCaseCompanyName(), "10-12B/A",
                LocalDate.parse("2026-08-15"), worstCaseFilingUrl(),
                worstCaseRatio(), null, null, true,
                "x".repeat(24_000), null,
                SpinStatus.DISTRIBUTED, null, null, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z",
                "2026-08-01T00:00:00Z", null, null, null);
    }

    private static EnrichedSpinCandidate worstCaseStructuredCandidate(String termSheet) {
        return new EnrichedSpinCandidate(
                123456789L, "ABCDE", worstCaseCompanyName(), "10-12B/A",
                "2026-08-15", worstCaseFilingUrl(), termSheet, true, worstCaseRatio(),
                "2026-08-15", "2026-08-15", "DISTRIBUTED",
                new java.math.BigDecimal("1234567890123.45"),
                new java.math.BigDecimal("1234567890123.45"),
                new java.math.BigDecimal("1234567890123.45"),
                "Diversified Industrial Manufacturing and Technology Holdings",
                1234567.89, 1234567.89, 0.1234, 365, true, "DISTRIBUTION_DATE", true,
                1234.56, 1234.56, 1234.56);
    }

    private static String worstCaseCompanyName() {
        return "A Very Long Spin-off Holdings Incorporated Industries Group";
    }

    private static String worstCaseFilingUrl() {
        return "https://www.sec.gov/Archives/edgar/data/1974640/000114036126028341/"
                + "ny20076262x2_defm14a_supplement_no_2.htm";
    }

    /** Longest phrase {@link SpinTermsParser}'s {@code DISTRIBUTION_RATIO} regex can match:
     *  {@code "twelve shares of " + 60 chars + " for every twelve shares"}. */
    private static String worstCaseRatio() {
        return "twelve shares of " + "z".repeat(60) + " for every twelve shares";
    }
}
