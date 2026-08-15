package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure/no-I/O tests for {@link TermEvidenceVerifier}: the deterministic guard that stops a
 * hallucinated date from ever reaching the database. All fixtures are synthetic and
 * hand-written — an invented company ("Acme Spinco"), invented dates.
 *
 * <p>Two traps this suite specifically pins (see the D5 plan): the extracted filing text breaks
 * lines mid-sentence, so the comparison must normalise whitespace on both sides; and the model
 * returns ISO-8601 while the filing prose spells the date out, so both forms must be accepted.
 */
class TermEvidenceVerifierTest {

    @Test
    void evidenceQuotedVerbatimAndContainingTheDateIsAccepted() {
        String termSheet = "Acme Spinco will be distributed to holders of record. "
                + "The distribution date is 2026-03-02, subject to customary conditions.";
        String evidence = "The distribution date is 2026-03-02, subject to customary conditions.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02")).isTrue();
    }

    @Test
    void evidenceQuotedVerbatimButNotContainingTheDateIsRejected() {
        String termSheet = "Acme Spinco will be distributed to holders of record. "
                + "The distribution date will be announced in a future filing.";
        String evidence = "The distribution date will be announced in a future filing.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02")).isFalse();
    }

    @Test
    void evidenceNotPresentInTheTermSheetAtAllIsRejected() {
        String termSheet = "Acme Spinco will be distributed to holders of record on 2026-03-02.";
        String evidence = "This sentence was never actually in the filing.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02")).isFalse();
    }

    @Test
    void evidenceDifferingOnlyInWhitespaceOrLineBreaksIsAccepted() {
        // Measured trap: the extracted filing text breaks lines mid-sentence, e.g. between
        // "record" and "date".
        String termSheet = "Holders of Acme Spinco common stock as of the close of business on "
                + "the record\ndate, which is 2026-03-02, will receive shares of NewCo.";
        String evidence = "the record date, which is 2026-03-02, will receive shares of NewCo.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02")).isTrue();
    }

    @Test
    void nullOrBlankEvidenceOrTermSheetIsRejected() {
        assertThat(TermEvidenceVerifier.supports(null, "some evidence", "2026-03-02")).isFalse();
        assertThat(TermEvidenceVerifier.supports("", "some evidence", "2026-03-02")).isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", null, "2026-03-02")).isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", "", "2026-03-02")).isFalse();
        assertThat(TermEvidenceVerifier.supports("   ", "some evidence", "2026-03-02")).isFalse();
        assertThat(TermEvidenceVerifier.supports("some term sheet text", "   ", "2026-03-02")).isFalse();
    }

    @Test
    void longFormDateSpellingIsAcceptedForAnIsoDate() {
        // Trap: the model returns ISO-8601 (2026-03-02) but the filing sentence spells the date
        // out ("March 2, 2026"). Both forms must be accepted.
        String termSheet = "The record date, which precedes the distribution date, will be "
                + "March 2, 2026, as fixed by the Acme Spinco board of directors.";
        String evidence = "The record date, which precedes the distribution date, will be "
                + "March 2, 2026, as fixed by the Acme Spinco board of directors.";

        assertThat(TermEvidenceVerifier.supports(termSheet, evidence, "2026-03-02")).isTrue();
    }
}
