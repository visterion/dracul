package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Synthetic Mon–Fri bar series (open = high = low = close). Prices are invented. */
final class BenchmarkFixtures {

    private BenchmarkFixtures() {
    }

    static List<OhlcBar> weekdayBars(LocalDate from, LocalDate to, String close, Map<LocalDate, String> overrides) {
        List<OhlcBar> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            BigDecimal c = new BigDecimal(overrides.getOrDefault(d, close));
            out.add(new OhlcBar(d, c, c, c, c, 1_000L));
        }
        return out;
    }

    static PriceSeries series(LocalDate from, LocalDate to, String close) {
        return PriceSeries.of(weekdayBars(from, to, close, Map.of()));
    }

    static PriceSeries series(LocalDate from, LocalDate to, String close, Map<LocalDate, String> overrides) {
        return PriceSeries.of(weekdayBars(from, to, close, overrides));
    }

    static LocalDate d(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
