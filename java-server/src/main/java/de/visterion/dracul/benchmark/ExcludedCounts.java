package de.visterion.dracul.benchmark;

/**
 * Events of one group that never reach a mean (final review I3). {@code noEntry} counts distinct
 * events with a terminal NO_ENTRY row; {@code noData} and {@code unknownCurrency} count distinct
 * never-measured (agent, symbol) pairs whose symbol is given up in benchmark_symbol_no_data
 * (≥ 3 nights) or has no known quote currency — a lower bound on the events behind them.
 */
public record ExcludedCounts(int noEntry, int noData, int unknownCurrency, int total) {

    public static final ExcludedCounts ZERO = new ExcludedCounts(0, 0, 0, 0);

    public static ExcludedCounts of(int noEntry, int noData, int unknownCurrency) {
        return new ExcludedCounts(noEntry, noData, unknownCurrency, noEntry + noData + unknownCurrency);
    }

    ExcludedCounts plus(int noEntry, int noData, int unknownCurrency) {
        return of(this.noEntry + noEntry, this.noData + noData, this.unknownCurrency + unknownCurrency);
    }
}
