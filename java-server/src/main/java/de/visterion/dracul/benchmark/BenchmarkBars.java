package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.AgoraMarketData;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Daily bars for the benchmark via the existing {@code get_ohlc} path — one symbol per call
 * (spec §3.2, review M1: the batch indicator path has no dates and no opens). Agora's {@code days}
 * argument is a trading-day bar count, max 1825 (plan ruling R14).
 */
@Component
public class BenchmarkBars {

    public static final String BENCHMARK_SYMBOL = "EUNL.DE";
    static final int MIN_BARS = 260;
    static final int MAX_BARS = 1825;
    static final int MARGIN_BARS = 30;

    private final AgoraMarketData marketData;

    public BenchmarkBars(AgoraMarketData marketData) {
        this.marketData = marketData;
    }

    /** @throws de.visterion.dracul.marketdata.MarketDataException when Agora cannot answer */
    public PriceSeries fetch(String symbol, int bars) {
        return PriceSeries.of(marketData.dailyOhlcHistory(symbol, bars));
    }

    public static int lookbackBars(LocalDate oldest, LocalDate today) {
        long days = Math.max(0, ChronoUnit.DAYS.between(oldest, today));
        long tradingDays = days * 5 / 7 + MARGIN_BARS;
        return (int) Math.min(MAX_BARS, Math.max(MIN_BARS, tradingDays));
    }
}
