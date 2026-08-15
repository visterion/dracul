package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpinTermsParserTest {
    private final SpinTermsParser parser = new SpinTermsParser();

    @Test
    void parsesWholeShareRatio() {
        SpinTerms t = parser.parse("...holders will receive one share of NewCo common stock for every "
                + "two shares of Parent common stock held as of the record date...");
        assertThat(t.distributionRatio()).contains("one share").contains("every two shares");
    }

    @Test
    void parsesFractionalRatio() {
        SpinTerms t = parser.parse("...shareholders will receive 0.25 shares of SpinCo common stock "
                + "for each share of Parent common stock they hold...");
        assertThat(t.distributionRatio()).contains("0.25 shares").contains("for each share");
    }

    @Test
    void parentTickerFromExchangeParenthetical() {
        assertThat(parser.parentTicker(
                "SpinCo will be separated from Big Parent Corporation (NYSE: BPC) in the distribution."))
                .isEqualTo("BPC");
        assertThat(parser.parentTicker("... (NASDAQ: ABCD) ...")).isEqualTo("ABCD");
    }

    @Test
    void parentTickerNullWhenOnlyName() {
        // a bare parent name (no exchange:ticker) is NOT resolved to a ticker
        assertThat(parser.parentTicker(
                "SpinCo will be separated from Big Parent Corporation in the distribution.")).isNull();
        assertThat(parser.parentTicker(null)).isNull();
    }

    @Test
    void unparseableYieldsNullRatio() {
        SpinTerms t = parser.parse("registration statement without ratio language");
        assertThat(t.distributionRatio()).isNull();
    }

    @Test
    void nullInputYieldsAllNullFields() {
        SpinTerms t = parser.parse(null);
        assertThat(t.distributionRatio()).isNull();
        assertThat(t.recordDate()).isNull();
        assertThat(t.distributionDate()).isNull();
    }

    /** Counter-proof for the four-round regex removal (see {@link SpinTermsParser} class javadoc):
     *  even a well-formed, unambiguous date sentence must NOT populate either date field anymore —
     *  the parser no longer attempts date extraction at all, regardless of how clean the sentence is.
     *  Dates now come exclusively from the model's belegpflichtig (evidence-verified) reading via
     *  the webhook. */
    @Test
    void wellFormedDateSentenceStillYieldsNullDates() {
        SpinTerms t = parser.parse("The record date for the distribution will be March 2, 2026.");
        assertThat(t.recordDate()).isNull();
        assertThat(t.distributionDate()).isNull();
    }

    @Test
    void crossBindingSentenceYieldsNullDates() {
        // The exact shape that broke every prior regex attempt (design doc §4.1, round 4): the
        // record-date clause names the distribution date in a subordinate clause before the date.
        SpinTerms t = parser.parse(
                "The record date, which precedes the distribution date, will be June 15, 2026.");
        assertThat(t.recordDate()).isNull();
        assertThat(t.distributionDate()).isNull();
    }
}
