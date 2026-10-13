package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Daily bars sorted ascending, one per date, without bars whose close is missing or ≤ 0
 *  ({@code AgoraMarketData} maps a missing value to 0 — never a price). */
public final class PriceSeries {

    private final List<OhlcBar> bars;
    private final List<LocalDate> dates;

    private PriceSeries(List<OhlcBar> bars) {
        this.bars = bars;
        this.dates = bars.stream().map(OhlcBar::date).toList();
    }

    public static PriceSeries of(List<OhlcBar> raw) {
        TreeMap<LocalDate, OhlcBar> byDate = new TreeMap<>();
        if (raw != null) {
            for (OhlcBar b : raw) {
                if (b == null || b.date() == null || b.close() == null || b.close().signum() <= 0) continue;
                byDate.put(b.date(), b);
            }
        }
        return new PriceSeries(List.copyOf(byDate.values()));
    }

    public static PriceSeries empty() {
        return new PriceSeries(List.of());
    }

    public List<OhlcBar> bars() {
        return bars;
    }

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    public OhlcBar lastBar() {
        return bars.isEmpty() ? null : bars.getLast();
    }

    public Optional<OhlcBar> lastBefore(LocalDate d) {
        int i = Collections.binarySearch(dates, d);
        int idx = (i >= 0 ? i : -(i + 1)) - 1;
        return idx >= 0 ? Optional.of(bars.get(idx)) : Optional.empty();
    }

    public Optional<OhlcBar> lastOnOrBefore(LocalDate d) {
        int i = Collections.binarySearch(dates, d);
        int idx = i >= 0 ? i : -(i + 1) - 1;
        return idx >= 0 ? Optional.of(bars.get(idx)) : Optional.empty();
    }

    public TradingCalendar calendar() {
        return TradingCalendar.of(dates);
    }
}
