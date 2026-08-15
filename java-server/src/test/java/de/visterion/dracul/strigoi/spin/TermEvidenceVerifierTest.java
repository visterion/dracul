package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;

import static de.visterion.dracul.strigoi.spin.TermEvidenceVerifier.Field.DISTRIBUTION_DATE;
import static de.visterion.dracul.strigoi.spin.TermEvidenceVerifier.Field.RECORD_DATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure/no-I/O tests for {@link TermEvidenceVerifier}: the deterministic guard that stops a
 * hallucinated date from ever reaching the database. All fixtures are synthetic and
 * hand-written — an invented company ("Acme Spinco"), invented dates.
 *
 * <p>D5 fix-round-1: the original "evidence occurs and contains the date" rule was too weak — a
 * sentence naming BOTH dates let a record date slip through as a submitted distributionDate, and
 * a bare date with no sentence around it counted as evidence. This suite pins the corrected
 * five-rule check (plan §"Die Prüfregeln") with the exact probe inputs the review ran against the
 * real class.
 */
class TermEvidenceVerifierTest {

    @Test
    void evidenceQuotedVerbatimAndContainingTheDateIsAccepted() {
        String termSheet = "Acme Spinco will be distributed to holders of record. "
                + "The distribution date is 2026-03-02, subject to customary conditions.";
        String evidence = "The distribution date is 2026-03-02, subject to customary conditions.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .isTrue();
    }

    @Test
    void evidenceQuotedVerbatimButNotContainingTheDateIsRejected() {
        String termSheet = "Acme Spinco will be distributed to holders of record. "
                + "The distribution date will be announced in a future filing, said the board.";
        String evidence = "The distribution date will be announced in a future filing, said the board.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .isFalse();
    }

    @Test
    void evidenceNotPresentInTheTermSheetAtAllIsRejected() {
        String termSheet = "Acme Spinco will be distributed to holders of record on 2026-03-02.";
        String evidence = "This sentence was never actually in the filing, forty characters long.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .isFalse();
    }

    @Test
    void evidenceDifferingOnlyInWhitespaceOrLineBreaksIsAccepted() {
        // Measured trap: the extracted filing text breaks lines mid-sentence, e.g. between
        // "record" and "date".
        String termSheet = "Holders of Acme Spinco common stock as of the close of business on "
                + "the record\ndate, which is 2026-03-02, will receive shares of NewCo.";
        String evidence = "the record date, which is 2026-03-02, will receive shares of NewCo.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", RECORD_DATE))
                .isTrue();
    }

    @Test
    void nullOrBlankEvidenceOrTermSheetIsRejected() {
        assertThat(TermEvidenceVerifier.supports(null, "some evidence, long enough to pass length",
                "2026-03-02", RECORD_DATE)).isFalse();
        assertThat(TermEvidenceVerifier.supports("", "some evidence, long enough to pass length",
                "2026-03-02", RECORD_DATE)).isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", null, "2026-03-02", RECORD_DATE))
                .isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", "", "2026-03-02", RECORD_DATE))
                .isFalse();
        assertThat(TermEvidenceVerifier.supports("   ", "some evidence, long enough to pass length",
                "2026-03-02", RECORD_DATE)).isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", "   ", "2026-03-02", RECORD_DATE))
                .isFalse();
    }

    @Test
    void longFormDateSpellingIsAcceptedForAnIsoDate() {
        // Trap: the model returns ISO-8601 (2026-03-02) but the filing sentence spells the date
        // out ("March 2, 2026"). Both forms must be accepted.
        String termSheet = "The distribution date, as fixed by the Acme Spinco board of directors, "
                + "will be March 2, 2026.";
        String evidence = "The distribution date, as fixed by the Acme Spinco board of directors, "
                + "will be March 2, 2026.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .isTrue();
    }

    // ================================================================================
    // Fix-round-1, C-1: the exact probes the review ran against the real class. The first two
    // used to return true (the bug); both must now return false.
    // ================================================================================

    @Test
    void aSentenceNamingBothDatesRejectsARecordDateSubmittedAsTheDistributionDate() {
        String text = "The record date is March 2, 2026 and the distribution date is March 16, 2026.";
        String evidence = text;

        // Reviewer probe: distributionDate = 2026-03-02 (the RECORD date's value) must be false.
        assertThat(TermEvidenceVerifier.supports(text, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .as("two dates in one sentence is ambiguous -- must reject regardless of which date matches")
                .isFalse();
        // The correct pairing must also be rejected: the sentence carries BOTH keywords, so rule 5
        // (own keyword present, other keyword absent) fails even though the date itself is right.
        assertThat(TermEvidenceVerifier.supports(text, evidence, "2026-03-16", DISTRIBUTION_DATE))
                .as("still ambiguous -- two dates in the evidence sentence, and it also names the "
                        + "other field's keyword")
                .isFalse();
    }

    @Test
    void aBareDateWithNoSurroundingSentenceIsNotEvidence() {
        String termSheet = "Somewhere in this filing, the distribution date is March 2, 2026, as fixed.";
        String evidence = "March 2, 2026";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .as("a bare date is not a sentence -- must fail the minimum-length rule")
                .isFalse();
    }

    @Test
    void theEntireDocumentAsEvidenceIsRejectedForCarryingMultipleDates() {
        String termSheet = "Acme Spinco was formed on January 5, 2026. The record date is "
                + "March 2, 2026. The distribution date is March 16, 2026. The fiscal year ends "
                + "December 31, 2026.";

        assertThat(TermEvidenceVerifier.supports(termSheet, termSheet, "2026-03-16", DISTRIBUTION_DATE))
                .as("the whole document trivially 'occurs in itself' but carries four distinct dates")
                .isFalse();
    }

    // ================================================================================
    // Fix-round-1, I-3: honest readings in additional spellings must be accepted, including the
    // NBSP trap (&nbsp; is pervasive in SEC HTML; \\s does not match U+00A0).
    // ================================================================================

    @Test
    void zeroPaddedDaySpellingIsAccepted() {
        String text = "The distribution date, set by the Acme Spinco board, will be March 02, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void coverPageAllCapsSpellingIsAccepted() {
        String text = "THE DISTRIBUTION DATE, SET BY THE ACME SPINCO BOARD, WILL BE MARCH 2, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void abbreviatedMonthWithPeriodIsAccepted() {
        String text = "The distribution date, set by the Acme Spinco board, will be Mar. 2, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void noCommaSpellingIsAccepted() {
        String text = "The distribution date, set by the Acme Spinco board, will be March 2 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void dayBeforeMonthSpellingIsAccepted() {
        String text = "The distribution date, set by the Acme Spinco board, will be 2 March 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void nonBreakingSpaceInsideTheEvidenceOrTextIsNormalised() {
        //   is a non-breaking space -- pervasive in SEC HTML via &nbsp;, and NOT matched by
        // the plain \s character class. The text carries NBSPs where the evidence has ordinary
        // spaces (and vice versa), pinning that normalisation runs on BOTH sides independently.
        String text = "The distribution date, set by the Acme Spinco board, will be March 2, 2026.";
        String evidence = "The distribution date, set by the Acme Spinco board, will be March 2, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, evidence, "2026-03-02", DISTRIBUTION_DATE))
                .isTrue();
    }

    // ================================================================================
    // Fix-round-1, rule 5: the field-specific full-keyword requirement (the plan's ADIG example).
    // ================================================================================

    @Test
    void recordDateFieldPassesWhenEvidenceCarriesOnlyTheRecordDateKeyword() {
        // The plan's real ADIG-style sentence: "distribution" appears as a bare word, never as the
        // full "distribution date" phrase, so this must pass as a RECORD_DATE reading.
        String text = "The record date for the distribution will be July 20, 2026, as fixed by the "
                + "Acme Spinco board of directors.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", RECORD_DATE)).isTrue();
    }

    @Test
    void distributionDateFieldPassesWhenEvidenceCarriesOnlyTheDistributionDateKeyword() {
        String text = "Holders should note that the spinoff will precede the distribution date of "
                + "April 1, 2026, as previously announced by Acme Spinco.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-04-01", DISTRIBUTION_DATE)).isTrue();
    }

    @Test
    void distributionDateFieldRejectsEvidenceThatAlsoCarriesTheRecordDateKeyword() {
        String text = "The record date, which precedes the distribution date, will be "
                + "June 15, 2026, as fixed by the Acme Spinco board.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-06-15", DISTRIBUTION_DATE)).isFalse();
    }

    @Test
    void recordDateFieldRejectsEvidenceThatAlsoCarriesTheDistributionDateKeyword() {
        String text = "The record date, which precedes the distribution date, will be "
                + "June 15, 2026, as fixed by the Acme Spinco board.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-06-15", RECORD_DATE)).isFalse();
    }

    @Test
    void recordDateFieldRejectsEvidenceCarryingOnlyTheDistributionDateKeyword() {
        String text = "Holders should note that the spinoff will precede the distribution date of "
                + "April 1, 2026, as previously announced by Acme Spinco.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-04-01", RECORD_DATE)).isFalse();
    }

    // ================================================================================
    // M-1 defensive: a calendar-impossible ISO date (e.g. Feb 30) must never be treated as valid,
    // whatever the evidence says.
    // ================================================================================

    @Test
    void calendarImpossibleIsoDateIsRejected() {
        String text = "The distribution date, set by the Acme Spinco board, will be February 30, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-02-30", DISTRIBUTION_DATE)).isFalse();
    }

    // ================================================================================
    // Fix-round-2, N-1 (C-1 still reachable): standard information-statement boilerplate never
    // says "record date" -- it says "holders of record". Without the "holders of record" keyword
    // family, that sentence has exactly one date and the bare word "distributed", so it wrongly
    // passed as a DISTRIBUTION_DATE reading -- the record date lands in distribution_date,
    // anchorSource becomes DISTRIBUTION_DATE, distributionDateConfirmed becomes true, and the
    // promotion window opens 1-2 weeks early. Exactly the harm C-1 named.
    // ================================================================================

    @Test
    void holdersOfRecordSentenceIsRejectedAsDistributionDateButAcceptedAsRecordDate() {
        String text = "Shares of NewCo common stock will be distributed to holders of record of "
                + "Acme common stock as of the close of business on July 20, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", DISTRIBUTION_DATE))
                .as("this is the RECORD date wearing distribution-flavoured prose around it -- "
                        + "must not pass as DISTRIBUTION_DATE")
                .isFalse();
        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", RECORD_DATE))
                .as("'holders of record ... as of ... July 20, 2026' IS the record date reading")
                .isTrue();
    }

    @Test
    void shareholdersOfRecordSentenceIsAcceptedAsRecordDate() {
        String text = "NewCo common stock will be issued to shareholders of record of Acme common "
                + "stock as of the close of business on July 20, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", RECORD_DATE)).isTrue();
        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", DISTRIBUTION_DATE)).isFalse();
    }

    @Test
    void recordHoldersSentenceIsAcceptedAsRecordDate() {
        String text = "NewCo shares will be issued to record holders of Acme common stock as of the "
                + "close of business on July 20, 2026.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", RECORD_DATE)).isTrue();
        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", DISTRIBUTION_DATE)).isFalse();
    }

    // ================================================================================
    // Fix-round-2, N-2: keyword regexes need word boundaries, or "distributed" matches inside
    // "undistributed" and a sentence about retained earnings is read as a distribution date.
    // ================================================================================

    @Test
    void undistributedEarningsSentenceIsNotADistributionDateReading() {
        String text = "Acme's undistributed earnings were measured as of July 20, 2026 by the "
                + "auditors for the information statement.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", DISTRIBUTION_DATE)).isFalse();
    }

    // ================================================================================
    // Fix-round-2 regression guard: the canonical ADIG sentence -- the one that motivated the
    // whole feature -- must still pass as RECORD_DATE after narrowing the keyword sets. This one
    // matters most: if it breaks, the feature is inert on the very filing it was built for.
    // ================================================================================

    @Test
    void theCanonicalAdigRecordDateSentenceStillPasses() {
        String text = "The record date for the distribution will be July 20, 2026, as fixed by the "
                + "Acme Spinco board of directors.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-07-20", RECORD_DATE)).isTrue();
    }

    @Test
    void aGenuineDistributionSentenceIsAcceptedAsDistributionDateOnly() {
        String text = "The shares will be distributed on August 3, 2026, to holders as of the "
                + "record date.";
        // Trimmed to a genuine distribution-only sentence for the DISTRIBUTION_DATE assertion
        // (the sentence above also names "record date" and would rightly fail rule 5 for either
        // field -- see the ambiguous-sentence tests already in this suite).
        String distributionOnly = "The shares will be distributed on August 3, 2026, as previously "
                + "announced by the Acme Spinco board.";

        assertThat(TermEvidenceVerifier.supports(distributionOnly, distributionOnly, "2026-08-03",
                DISTRIBUTION_DATE)).isTrue();
        assertThat(TermEvidenceVerifier.supports(distributionOnly, distributionOnly, "2026-08-03",
                RECORD_DATE)).isFalse();
    }

    // ================================================================================
    // Fix-round-2 re-review note: rule 3 counts DISTINCT dates via a Set<LocalDate>, so the same
    // calendar date written twice (even in two different spellings) counts once, not twice -- this
    // is deliberate, not a bug, and is exercised here so the next reader doesn't "fix" it.
    // ================================================================================

    @Test
    void theSameDateRepeatedInTwoSpellingsCountsAsOneDateNotTwo() {
        String text = "The distribution date will be August 3, 2026 (i.e. 2026-08-03), as fixed by "
                + "the Acme Spinco board.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-08-03", DISTRIBUTION_DATE)).isTrue();
    }

    /** A date RANGE yields zero dates (neither endpoint matches the single-date patterns as
     *  written) and is rejected -- a conservative false negative, deliberately left as-is. */
    @Test
    void aDateRangeYieldsNoDatesAndIsRejected() {
        String text = "The distribution date will fall between March 2-6, 2026, subject to final "
                + "board approval.";

        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-02", DISTRIBUTION_DATE)).isFalse();
        assertThat(TermEvidenceVerifier.supports(text, text, "2026-03-06", DISTRIBUTION_DATE)).isFalse();
    }
}
