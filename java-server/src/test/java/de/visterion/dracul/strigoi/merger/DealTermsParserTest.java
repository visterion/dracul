package de.visterion.dracul.strigoi.merger;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DealTermsParserTest {
    private final DealTermsParser parser = new DealTermsParser();

    @Test
    void parsesCashDeal() {
        DealTerms t = parser.parse("...each share of common stock will be converted into the right "
                + "to receive $54.20 in cash... termination fee of approximately $85 million...");
        assertThat(t.offerPrice()).isEqualByComparingTo("54.20");
        assertThat(t.considerationType()).isEqualTo("cash");
        assertThat(t.breakFee()).isEqualTo("$85 million");
    }

    @Test
    void parsesStockDeal() {
        DealTerms t = parser.parse("...will receive 0.5230 shares of Parent common stock for each share...");
        assertThat(t.exchangeRatio()).isEqualTo("0.5230");
        assertThat(t.considerationType()).isEqualTo("stock");
        assertThat(t.offerPrice()).isNull();
    }

    @Test
    void parsesMixedDeal() {
        DealTerms t = parser.parse("...$26.00 in cash and 0.30 shares of Acquirer stock per share...");
        assertThat(t.considerationType()).isEqualTo("mixed");
    }

    @Test
    void ignoresParValueBoilerplate() {
        DealTerms t = parser.parse("...shares of common stock, par value of $0.001 per share, "
                + "of the Company...");
        assertThat(t.offerPrice()).isNull();
        assertThat(t.considerationType()).isNull();
    }

    @Test
    void skipsParValueMatchAndFindsRealOfferLater() {
        DealTerms t = parser.parse("...each share of common stock, par value of $0.001 per share, "
                + "will be converted into the right to receive $54.20 in cash per share...");
        assertThat(t.offerPrice()).isEqualByComparingTo("54.20");
        assertThat(t.considerationType()).isEqualTo("cash");
    }

    @Test
    void unparseableYieldsNulls() {
        assertThat(parser.parse("no deal language here").offerPrice()).isNull();
        assertThat(parser.parse(null).considerationType()).isNull();
    }

    @Test
    void parsesAgreementDateFromDatedAsOf() {
        DealTerms t = parser.parse("Agreement and Plan of Merger, dated as of March 15, 2026, "
                + "by and among the Company and Parent...");
        assertThat(t.agreementDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void parsesAgreementDateFromDatedWithoutAsOf() {
        DealTerms t = parser.parse("...the Merger Agreement dated April 1, 2026 provides...");
        assertThat(t.agreementDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void parsesAgreementDateFromEnteredIntoOn() {
        DealTerms t = parser.parse("The Company entered into the merger agreement with Parent "
                + "on January 9, 2026, following board approval.");
        assertThat(t.agreementDate()).isEqualTo(LocalDate.of(2026, 1, 9));
    }

    @Test
    void expectedCloseQuarterMapsToQuarterEnd() {
        DealTerms t = parser.parse("The transaction is expected to close in the second quarter of 2026, "
                + "subject to customary conditions.");
        assertThat(t.expectedCloseDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void expectedCloseFourthQuarterMapsToYearEnd() {
        DealTerms t = parser.parse("...expected to be completed in the fourth quarter of 2026...");
        assertThat(t.expectedCloseDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void expectedCloseHalfMapsToHalfEnd() {
        DealTerms t = parser.parse("The merger is expected to close in the first half of 2027.");
        assertThat(t.expectedCloseDate()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void expectedCloseExplicitByDate() {
        DealTerms t = parser.parse("The transaction is expected to close by September 30, 2026, "
                + "subject to customary closing conditions.");
        assertThat(t.expectedCloseDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void outsideDateIsSeparateAndNotUsedAsExpectedClose() {
        // Only an outside/End Date is present — expectedCloseDate must stay null, outsideDate set.
        DealTerms t = parser.parse("...if the Merger has not been consummated by the End Date of "
                + "December 31, 2026, either party may terminate...");
        assertThat(t.outsideDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(t.expectedCloseDate()).isNull();
    }

    @Test
    void noDatesYieldNulls() {
        DealTerms t = parser.parse("shareholders will receive $54.20 in cash per share");
        assertThat(t.agreementDate()).isNull();
        assertThat(t.expectedCloseDate()).isNull();
        assertThat(t.outsideDate()).isNull();
    }

    @Test
    void impossibleCalendarDateIsRejected() {
        DealTerms t = parser.parse("Agreement and Plan of Merger, dated as of February 30, 2026...");
        assertThat(t.agreementDate()).isNull();
    }
}
