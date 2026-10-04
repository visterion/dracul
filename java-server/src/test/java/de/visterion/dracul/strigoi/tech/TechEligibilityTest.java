package de.visterion.dracul.strigoi.tech;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-03 §4.2 — code rejects only what is clearly out of scope. */
class TechEligibilityTest {

    private static final BigDecimal MIN = new BigDecimal("20000");

    private static TechEligibility.Inputs inputs(String type, String ccy, String ticker,
            String mcap, boolean held, boolean pending, boolean recent) {
        return new TechEligibility.Inputs("SYNA", type, ccy, ticker,
                mcap == null ? null : new BigDecimal(mcap), held, pending, recent);
    }

    private static TechEligibility.Verdict eval(TechEligibility.Inputs in) {
        return TechEligibility.evaluate(in, MIN, "USD");
    }

    @Test
    void aConfirmedLargeUsEquityIsEligible() {
        var v = eval(inputs("EQUITY", "USD", "SYNA", "50000", false, false, false));
        assertThat(v.eligible()).isTrue();
        assertThat(v.reasons()).isEmpty();
        assertThat(v.notes()).isEmpty();
    }

    /** A foreign primary listing (ADR): the profile's market cap is in another currency — the
     *  size check is reported, not enforced (LIQUIDITY guards tradability later). */
    @Test
    void anAdrPassesWithMarketCapUnverified() {
        var v = eval(inputs("EQUITY", "USD", "SYNA.F", "12", false, false, false));
        assertThat(v.eligible()).isTrue();
        assertThat(v.notes()).containsExactly("market_cap_unverified");
    }

    @Test
    void anEtfIsRejected() {
        var v = eval(inputs("ETF", "USD", "SYNA", "50000", false, false, false));
        assertThat(v.eligible()).isFalse();
        assertThat(v.reasons()).containsExactly("not_equity:ETF");
    }

    @Test
    void aBlankProfileIsDataUnavailableNeverASilentPass() {
        var v = eval(inputs("EQUITY", "USD", " ", null, false, false, false));
        assertThat(v.eligible()).isFalse();
        assertThat(v.reasons()).containsExactly("data_unavailable:profile");
    }

    @Test
    void missingTypeCurrencyOrMarketCapAreDataUnavailable() {
        assertThat(eval(inputs(null, "USD", "SYNA", "50000", false, false, false)).reasons())
                .containsExactly("data_unavailable:instrument_type");
        assertThat(eval(inputs("EQUITY", null, "SYNA", "50000", false, false, false)).reasons())
                .containsExactly("data_unavailable:quote_currency");
        assertThat(eval(inputs("EQUITY", "USD", "SYNA", null, false, false, false)).reasons())
                .containsExactly("data_unavailable:market_cap");
    }

    @Test
    void nonUsdSmallHeldPendingAndRecentlyExitedAreRejected() {
        assertThat(eval(inputs("EQUITY", "EUR", "SYNA", "50000", false, false, false)).reasons())
                .containsExactly("quote_currency:EUR");
        assertThat(eval(inputs("EQUITY", "USD", "SYNA", "19999", false, false, false)).reasons())
                .containsExactly("market_cap_below_min");
        assertThat(eval(inputs("EQUITY", "USD", "SYNA", "20000", false, false, false)).eligible())
                .isTrue();
        assertThat(eval(inputs("EQUITY", "USD", "SYNA", "50000", true, true, true)).reasons())
                .containsExactly("already_held", "already_pending", "recently_exited");
    }
}
