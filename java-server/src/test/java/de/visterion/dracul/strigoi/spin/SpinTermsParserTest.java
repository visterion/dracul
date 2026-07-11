package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpinTermsParserTest {
    private final SpinTermsParser parser = new SpinTermsParser();

    @Test
    void parsesRatioAndDates() {
        SpinTerms t = parser.parse("...one share of NewCo common stock for every three shares of "
                + "Parent common stock held as of the record date of March 15, 2026, with the "
                + "distribution expected to occur on April 1, 2026...");
        assertThat(t.distributionRatio()).contains("one share").contains("every three shares");
        assertThat(t.recordDate()).isEqualTo("2026-03-15");
        assertThat(t.distributionDate()).isEqualTo("2026-04-01");
    }

    @Test
    void unparseableYieldsNulls() {
        SpinTerms t = parser.parse("registration statement without ratio language");
        assertThat(t.distributionRatio()).isNull();
        assertThat(t.recordDate()).isNull();
    }

    @Test
    void unparseableTextYieldsNullDistributionDate() {
        SpinTerms t = parser.parse("registration statement without ratio language");
        assertThat(t.distributionDate()).isNull();
    }

    @Test
    void parsesFractionalRatio() {
        SpinTerms t = parser.parse("...shareholders will receive 0.25 shares of SpinCo common stock "
                + "for each share of Parent common stock they hold...");
        assertThat(t.distributionRatio()).contains("0.25 shares").contains("for each share");
    }

    @Test
    void parsesDistributedOnPhrasing() {
        SpinTerms t = parser.parse("...the shares will be distributed on June 30, 2026 to holders of record...");
        assertThat(t.distributionDate()).isEqualTo("2026-06-30");
    }

    @Test
    void nullInputYieldsAllNullFields() {
        SpinTerms t = parser.parse(null);
        assertThat(t.distributionRatio()).isNull();
        assertThat(t.recordDate()).isNull();
        assertThat(t.distributionDate()).isNull();
    }

    @Test
    void neverThrowsOnMalformedDateText() {
        // "record date" followed by garbage that matches the month-name pattern loosely but
        // fails DateTimeFormatter parsing (e.g. an invalid day) must degrade to null, not throw.
        SpinTerms t = parser.parse("the record date is expected to be set in February 30, 2026 pending approval");
        assertThat(t.recordDate()).isNull();
    }

    @Test
    void genericProxyBoilerplateDoesNotFalselyMatchRecordDate() {
        // Generic proxy-instruction boilerplate mentions "record date" without ever stating an
        // actual date nearby — must not throw and must yield null rather than grabbing an
        // unrelated date from elsewhere in the text via runaway context.
        SpinTerms t = parser.parse("Only shareholders of record date will be entitled to vote at the "
                + "annual meeting. Please consult your broker for further instructions regarding "
                + "proxy materials and voting procedures under applicable state law.");
        assertThat(t.recordDate()).isNull();
    }
}
