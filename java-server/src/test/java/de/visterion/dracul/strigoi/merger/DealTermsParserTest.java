package de.visterion.dracul.strigoi.merger;

import org.junit.jupiter.api.Test;

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
    void unparseableYieldsNulls() {
        assertThat(parser.parse("no deal language here").offerPrice()).isNull();
        assertThat(parser.parse(null).considerationType()).isNull();
    }
}
