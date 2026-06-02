package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.yahoo.EarningsEvent;
import de.visterion.dracul.marketdata.StubMarketDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EchoPeadScreenerTest {

    private StubMarketDataPort marketData;
    private EchoPeadScreener screener;

    private static EarningsEvent ev(String sym, double estimate, double actual, double surprisePct) {
        return new EarningsEvent(sym, sym + " Inc.", LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(actual), BigDecimal.valueOf(estimate), BigDecimal.valueOf(surprisePct));
    }

    @BeforeEach
    void setUp() {
        marketData = new StubMarketDataPort();
        screener = new EchoPeadScreener(marketData, new BigDecimal("5.0"), new BigDecimal("5.0"));
    }

    @Test
    void keepsPositiveSurpriseAboveThreshold() {
        marketData.register("AAPL", "Apple Inc.", 190.0);
        var out = screener.screen(List.of(ev("AAPL", 1.50, 1.65, 10.0)));
        assertThat(out).hasSize(1);
        var c = out.get(0);
        assertThat(c.symbol()).isEqualTo("AAPL");
        assertThat(c.currentPrice()).isEqualByComparingTo("190.0");
        assertThat(c.surprisePercent()).isEqualByComparingTo("10.0");
    }

    @Test
    void dropsNegativeSurprise() {
        marketData.register("MISS", "Miss Co", 50.0);
        var out = screener.screen(List.of(ev("MISS", 2.00, 1.80, -10.0)));
        assertThat(out).isEmpty();
    }

    @Test
    void dropsSurpriseBelowThreshold() {
        marketData.register("TINY", "Tiny Beat", 50.0);
        var out = screener.screen(List.of(ev("TINY", 1.00, 1.02, 2.0)));
        assertThat(out).isEmpty();
    }

    @Test
    void dropsBelowMinPrice() {
        marketData.register("PENNY", "Penny Co", 2.50);
        var out = screener.screen(List.of(ev("PENNY", 0.10, 0.20, 100.0)));
        assertThat(out).isEmpty();
    }

    @Test
    void dropsWhenPriceUnavailable() {
        var out = screener.screen(List.of(ev("GHOST", 1.00, 1.20, 20.0)));
        assertThat(out).isEmpty();
    }

    @Test
    void dropsWhenEpsMissing() {
        marketData.register("NEW", "New Issue", 30.0);
        var noEps = new EarningsEvent("NEW", "New Issue", LocalDate.of(2026, 5, 20),
                null, null, BigDecimal.valueOf(50.0));
        assertThat(screener.screen(List.of(noEps))).isEmpty();
    }
}
