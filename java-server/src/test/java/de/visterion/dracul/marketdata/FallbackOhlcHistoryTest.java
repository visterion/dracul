package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackOhlcHistoryTest {

    private OhlcBar bar() {
        return new OhlcBar(LocalDate.of(2026, 1, 2), new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("9"), new BigDecimal("10.5"), 1000L);
    }

    private MarketDataPort throwing() {
        return new MarketDataPort() {
            public MarketData resolve(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no", null); }
            public List<OhlcBar> dailyOhlcHistory(String s, int days) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no", null); }
        };
    }

    private MarketDataPort serving(List<OhlcBar> bars) {
        return new MarketDataPort() {
            public MarketData resolve(String s) { return new MarketData("X", BigDecimal.ONE, List.of()); }
            public List<OhlcBar> dailyOhlcHistory(String s, int days) { return bars; }
        };
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryThrows() {
        var port = new FallbackMarketDataPort(throwing(), serving(List.of(bar())));
        assertThat(port.dailyOhlcHistory("AAPL", 260)).hasSize(1);
    }

    @Test
    void defaultInterfaceMethodThrowsUnavailable() {
        MarketDataPort noHistory = symbol -> new MarketData("X", BigDecimal.ONE, List.of());
        org.junit.jupiter.api.Assertions.assertThrows(MarketDataException.class,
                () -> noHistory.dailyOhlcHistory("AAPL", 260));
    }
}
