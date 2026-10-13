package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

class TradingCalendarTest {

    /** Mon 2026-03-02 .. Fri 2026-03-13: ten trading days. */
    private final TradingCalendar cal = TradingCalendar.of(datesOf(d(2026, 3, 2), d(2026, 3, 13)));

    private static List<LocalDate> datesOf(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        BenchmarkFixtures.weekdayBars(from, to, "1", java.util.Map.of()).forEach(b -> out.add(b.date()));
        return out;
    }

    @Test
    void countBetweenCountsRealDaysInTheHalfOpenInterval() {
        assertThat(cal.countBetween(d(2026, 3, 2), d(2026, 3, 6))).isEqualTo(4);
        assertThat(cal.countBetween(d(2026, 3, 6), d(2026, 3, 2))).isZero();
        assertThat(cal.countBetween(d(2026, 2, 27), d(2026, 3, 3))).isEqualTo(2);
    }

    @Test
    void countBetweenExtrapolatesWeekdaysBeforeTheFirstDayButNeverAfterTheLast() {
        // (2026-02-20, 2026-03-01] extrapolated = 23..27 Feb = 5, plus real 2026-03-02 = 6
        assertThat(cal.countBetween(d(2026, 2, 20), d(2026, 3, 2))).isEqualTo(6);
        assertThat(cal.countBetween(d(2026, 3, 12), d(2026, 3, 20))).isEqualTo(1);
    }

    @Test
    void aMissingDayIsAHoliday() {
        List<LocalDate> days = datesOf(d(2026, 3, 2), d(2026, 3, 13));
        days.remove(d(2026, 3, 4));
        assertThat(TradingCalendar.of(days).countBetween(d(2026, 3, 2), d(2026, 3, 6))).isEqualTo(3);
    }

    @Test
    void countAfterCountsRealDaysStrictlyAfter() {
        assertThat(cal.countAfter(d(2026, 3, 11))).isEqualTo(2);
        assertThat(cal.countAfter(d(2026, 3, 13))).isZero();
        assertThat(cal.countAfter(d(2026, 3, 1))).isEqualTo(10);
    }

    @Test
    void minusWalksRealDaysThenWeekdaysBeforeTheFirst() {
        assertThat(cal.minus(d(2026, 3, 6), 4)).isEqualTo(d(2026, 3, 2));
        assertThat(cal.minus(d(2026, 3, 6), 5)).isEqualTo(d(2026, 2, 27));
        assertThat(cal.minus(d(2026, 2, 20), 1)).isEqualTo(d(2026, 2, 19));
        assertThat(cal.minus(d(2026, 3, 9), 0)).isEqualTo(d(2026, 3, 9));
    }

    @Test
    void plusReturnsTheNthRealDayOrEmpty() {
        assertThat(cal.plus(d(2026, 3, 2), 1)).contains(d(2026, 3, 3));
        assertThat(cal.plus(d(2026, 3, 2), 9)).contains(d(2026, 3, 13));
        assertThat(cal.plus(d(2026, 3, 2), 10)).isEmpty();
        assertThat(cal.plus(d(2026, 2, 27), 1)).contains(d(2026, 3, 2));
    }

    @Test
    void firstAndLast() {
        assertThat(cal.first()).isEqualTo(d(2026, 3, 2));
        assertThat(cal.last()).isEqualTo(d(2026, 3, 13));
        assertThat(TradingCalendar.of(List.of()).isEmpty()).isTrue();
    }
}
