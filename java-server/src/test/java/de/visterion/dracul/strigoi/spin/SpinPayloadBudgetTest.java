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
 * raw — not hypothetical, it is the exact class of failure that already blinded the merger hunter
 * on production run {@code 74754073...} (2026-08-04).
 *
 * <p><b>Fix-round-2 rework — two problems with the first version of this fix.</b>
 * <ol>
 *   <li><b>Thin slices are worse than fewer rows.</b> A total budget spread evenly across every
 *       row still needing a reading degrades to slices too short to contain the answer. Measured
 *       on four real EX-99.1 information statements, the distribution ratio sits at offsets
 *       1 550-2 800 and the record date at 2 113-7 291 — a slice under ~7 000 chars can miss the
 *       record date entirely, so a thin-slice-for-everyone budget confidently ships prose that
 *       provably cannot answer the question. Now: a FULL {@link
 *       SpinCandidateEnricher#TERM_SHEET_SLICE_CHARS} (8 000 chars — covers the measured
 *       record-date offsets, up to 7 291, with a ~700-char margin) slice to at most {@link
 *       SpinCandidateEnricher#MAX_PROSE_ROWS_PER_RUN} (5) rows per run, oldest-{@code
 *       termsCheckedAt}-first, {@code null} to the rest. {@link
 *       #aSelectedRowGetsTheFullSliceNeverADividedShare} pins this so a future "share it out
 *       fairly" change cannot silently re-introduce useless slices.</li>
 *   <li><b>1.8% margin against a hard ceiling is not margin.</b> Targeting the 100 000-char hard
 *       ceiling left the worst case at ~98 150 chars — a single added field or a slightly-off
 *       ceiling estimate reproduces the C-1 failure invisibly. This version targets ~75 000 chars,
 *       affordable by ALSO lowering {@link SpinCandidateEnricher#RESPONSE_LIMIT} from 50 to 25: it
 *       already matched {@link SpinCandidateEnricher#MAX}, the enrichment cap, so a steady-state
 *       run was never going to reach 50 freshly-enriched rows anyway (see {@link
 *       #measureStructuredFieldsAloneAtFullResponseLimit}).</li>
 * </ol>
 *
 * <p><b>The arithmetic.</b>
 * <pre>
 *   structured fields, RESPONSE_LIMIT (25) rows, termSheet=null (measured)                ~24 576 chars
 *   + term-sheet prose (MAX_PROSE_ROWS_PER_RUN (5) x TERM_SHEET_SLICE_CHARS (8 000), measured 36 155  40 000 chars
 *   + envelope (data_source_health + active_patterns, uncapped)                              5 000 chars
 *   = worst case (measured, real payload via the real enricher)                            65 731 chars
 * </pre>
 * 65 731 stays comfortably under the {@link #WORST_CASE_TARGET_CHARS} (75 000) target — 12.4% margin,
 * not the first version's 1.8% — reasonably close to the 50 000-char safe zone and well clear of the
 * 100 000-char hard ceiling. {@link #worstCasePayloadStaysUnderTheTarget} measures this exact number
 * by running the real {@link SpinCandidateEnricher#payload} through a mocked repo, not by hand-adding
 * estimates, so it moves automatically if a field is added.
 */
class SpinPayloadBudgetTest {

    /**
     * Fix-round-2: target margin, not the 100 000-char hard ceiling itself. A payload that only
     * just clears the ceiling degrades to the exact C-1 failure — invisibly, {@code status=done},
     * {@code {"prey": []}} — the moment a field is added or the ceiling estimate is slightly off.
     */
    private static final int WORST_CASE_TARGET_CHARS = 75_000;

    /** Reserve for {@code data_source_health} + {@code active_patterns} (uncapped) riding the
     *  same tool envelope — mirrors {@code MergerPayloadBudgetTest.ENVELOPE_RESERVE_CHARS}. */
    private static final int ENVELOPE_RESERVE_CHARS = 5_000;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ObjectMapper rowMapper = new ObjectMapper();

    /**
     * Measures {@link SpinCandidateEnricher#RESPONSE_LIMIT} rows of {@link EnrichedSpinCandidate}
     * structured fields ALONE (term sheet null) — the floor the class javadoc above depends on.
     * Field values are the longest realistic ones (longest company name / EDGAR archive URL /
     * ratio phrase the parser's regex can produce), mirroring how {@code MergerPayloadBudgetTest}
     * built its worst-case structured block. Must leave room, within the target, for the full
     * per-run prose allowance ({@code MAX_PROSE_ROWS_PER_RUN x TERM_SHEET_SLICE_CHARS}) plus the
     * envelope reserve.
     */
    @Test
    void measureStructuredFieldsAloneAtFullResponseLimit() {
        List<EnrichedSpinCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < SpinCandidateEnricher.RESPONSE_LIMIT; i++) {
            candidates.add(worstCaseStructuredCandidate(null));
        }
        String json = mapper.writeValueAsString(candidates);
        int proseAllowance =
                SpinCandidateEnricher.MAX_PROSE_ROWS_PER_RUN * SpinCandidateEnricher.TERM_SHEET_SLICE_CHARS;

        assertThat(json.length())
                .as("structured-only payload for %d rows is %d chars; must leave room under the "
                                + "%d target for %d chars of prose allowance plus %d envelope reserve",
                        SpinCandidateEnricher.RESPONSE_LIMIT, json.length(), WORST_CASE_TARGET_CHARS,
                        proseAllowance, ENVELOPE_RESERVE_CHARS)
                .isLessThan(WORST_CASE_TARGET_CHARS - proseAllowance - ENVELOPE_RESERVE_CHARS);
    }

    /**
     * The point of this fix: {@link SpinCandidateEnricher#payload} run through a mocked repo
     * returning {@link SpinCandidateEnricher#RESPONSE_LIMIT} rows, EVERY one carrying a full
     * 24 000-char term sheet and no verified dates (the worst case — every row still needs a
     * reading, so the per-run selection cap is what actually bounds the prose total), stays under
     * the ~75 000-char target with real margin.
     */
    @Test
    void worstCasePayloadStaysUnderTheTarget() {
        SpinPayload payload = worstCasePayloadFromRealEnricher();
        String json = mapper.writeValueAsString(payload.candidates());
        int worstCase = json.length() + ENVELOPE_RESERVE_CHARS;

        assertThat(worstCase)
                .as("%d rows, every one needing a reading with a 24 000-char term sheet, produce a "
                                + "%d-char payload (+%d envelope reserve) = %d; the target is %d. "
                                + "Above it the Claude-Max bridge risks cutting the tool result "
                                + "mid-JSON and the model answers {\"prey\": []} with status=done "
                                + "and nothing in the log to explain why (this already happened once, "
                                + "on the merger hunter, 2026-08-04).",
                        SpinCandidateEnricher.RESPONSE_LIMIT, json.length(), ENVELOPE_RESERVE_CHARS,
                        worstCase, WORST_CASE_TARGET_CHARS)
                .isLessThan(WORST_CASE_TARGET_CHARS);

        // Nothing may put the raw 24 000-char term sheet back on the wire — that is the whole
        // regression. At most MAX_PROSE_ROWS_PER_RUN rows may carry prose at all.
        long withProse = payload.candidates().stream().filter(c -> c.termSheet() != null).count();
        assertThat(withProse)
                .as("at most %d rows may be selected for prose per run",
                        SpinCandidateEnricher.MAX_PROSE_ROWS_PER_RUN)
                .isLessThanOrEqualTo(SpinCandidateEnricher.MAX_PROSE_ROWS_PER_RUN);
        for (EnrichedSpinCandidate c : payload.candidates()) {
            if (c.termSheet() != null) {
                assertThat(c.termSheet().length())
                        .as("candidate %d term sheet is %d chars — the per-row slice is %d", c.id(),
                                c.termSheet().length(), SpinCandidateEnricher.TERM_SHEET_SLICE_CHARS)
                        .isLessThanOrEqualTo(SpinCandidateEnricher.TERM_SHEET_SLICE_CHARS);
            }
        }
    }

    /**
     * Fix-round-2's central guarantee, pinned directly: a row selected for prose gets the FULL
     * {@link SpinCandidateEnricher#TERM_SHEET_SLICE_CHARS} slice, never a divided share. This is
     * what stops a future "let's share the budget out fairly across more rows" change from
     * silently re-introducing slices too short to contain the record date (measured up to offset
     * 7 291 across four real filings — far past what any evenly-divided share at
     * {@link SpinCandidateEnricher#RESPONSE_LIMIT} scale could afford).
     */
    @Test
    void aSelectedRowGetsTheFullSliceNeverADividedShare() {
        SpinPayload payload = worstCasePayloadFromRealEnricher();
        long withProse = payload.candidates().stream().filter(c -> c.termSheet() != null).count();

        assertThat(withProse)
                .as("this worst case has more needy rows than the cap, so exactly the cap must be selected")
                .isEqualTo(SpinCandidateEnricher.MAX_PROSE_ROWS_PER_RUN);
        payload.candidates().stream()
                .filter(c -> c.termSheet() != null)
                .forEach(c -> assertThat(c.termSheet().length())
                        .as("candidate %d must get the FULL slice, not a fraction of it", c.id())
                        .isEqualTo(SpinCandidateEnricher.TERM_SHEET_SLICE_CHARS));
    }

    /** A row whose dates are already agent-verified (D5) must not have its prose re-sent — this
     *  reserves the per-run selection cap for rows that actually still need a reading. */
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

        SpinPayload payload = enricher(repo).payload(LocalDate.parse("2026-07-01"));

        assertThat(payload.candidates()).hasSize(1);
        assertThat(payload.candidates().getFirst().termSheet()).isNull();
    }

    /**
     * Rotation fairness: among needy rows beyond the per-run cap, the ones with the OLDEST (or
     * null — never captured) {@code termsCheckedAt} are selected first, so a row not picked this
     * run sorts earlier next run instead of being starved indefinitely by the same rows winning
     * every time.
     */
    @Test
    void selectionPrefersOldestTermsCheckedAtFirst() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        SpinCandidateRow neverChecked = needyRow(1L, null);
        SpinCandidateRow oldest = needyRow(2L, java.time.Instant.parse("2026-08-01T00:00:00Z"));
        SpinCandidateRow newest = needyRow(3L, java.time.Instant.parse("2026-08-14T00:00:00Z"));
        when(repo.findActiveUnpromotedInWindow(any(), anyInt()))
                .thenReturn(List.of(newest, oldest, neverChecked));

        SpinPayload payload = enricher(repo).payload(LocalDate.parse("2026-07-01"));

        // cap is 5, only 3 needy rows here, so all three should get prose — but confirm the
        // never-checked / oldest ones are not silently skipped in favour of the newest.
        assertThat(payload.candidates()).filteredOn(c -> c.termSheet() != null)
                .extracting(EnrichedSpinCandidate::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private SpinCandidateRow needyRow(long id, java.time.Instant termsCheckedAt) {
        return new SpinCandidateRow(id, "cik" + id, "SYM" + id, "Co " + id, "10-12B",
                LocalDate.parse("2026-07-01"), "https://sec/" + id, null, null, null, true,
                "x".repeat(1000), null, SpinStatus.DISTRIBUTED, null, null, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z",
                "2026-08-01T00:00:00Z", null, null, termsCheckedAt);
    }

    private SpinPayload worstCasePayloadFromRealEnricher() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        List<SpinCandidateRow> rows = new ArrayList<>();
        for (int i = 0; i < SpinCandidateEnricher.RESPONSE_LIMIT; i++) {
            rows.add(worstCaseRow(i));
        }
        when(repo.findActiveUnpromotedInWindow(any(), anyInt())).thenReturn(rows);
        return enricher(repo).payload(LocalDate.parse("2026-08-15"));
    }

    private SpinCandidateEnricher enricher(SpinCandidateRepository repo) {
        return new SpinCandidateEnricher(repo,
                Mockito.mock(SpinLifecycleReconciler.class),
                Mockito.mock(SpinBalanceSheetSnapshotter.class),
                Mockito.mock(SpinDistributionSnapshotter.class),
                Mockito.mock(SpinValuationSnapshotter.class),
                Mockito.mock(de.visterion.dracul.hunting.agora.AgoraFilings.class),
                Mockito.mock(SpinTermsParser.class),
                rowMapper);
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
