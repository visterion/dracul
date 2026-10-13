package de.visterion.dracul.benchmark;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The EUNL.DE trading-day calendar (spec §3.2: reference calendar for gap rules, horizon dates and
 * delisting). Built from the benchmark's own bars. Before its first day it extrapolates Mon–Fri so
 * old picked windows still resolve; after its last day nothing is counted (the future has no
 * trading days yet).
 */
public final class TradingCalendar {

    private final List<LocalDate> days;

    private TradingCalendar(List<LocalDate> days) {
        this.days = days;
    }

    public static TradingCalendar of(Collection<LocalDate> dates) {
        return new TradingCalendar(dates.stream().filter(Objects::nonNull).distinct().sorted().toList());
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    public LocalDate first() {
        return days.isEmpty() ? null : days.getFirst();
    }

    public LocalDate last() {
        return days.isEmpty() ? null : days.getLast();
    }

    /** Trading days d with {@code from < d <= to}. */
    public int countBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        if (days.isEmpty()) return weekdaysBetween(from, to);
        int n = 0;
        LocalDate first = days.getFirst();
        if (from.isBefore(first)) {
            LocalDate end = to.isBefore(first) ? to : first.minusDays(1);
            n += weekdaysBetween(from, end);
        }
        return n + countUpTo(to) - countUpTo(from);
    }

    /** Real trading days strictly after {@code d}. */
    public int countAfter(LocalDate d) {
        return days.size() - countUpTo(d);
    }

    /** The trading day {@code n} trading days before {@code d}. */
    public LocalDate minus(LocalDate d, int n) {
        if (n <= 0) return d;
        int before = countBefore(d);
        if (before >= n) return days.get(before - n);
        int remaining = n - before;
        LocalDate cursor = (days.isEmpty() || !d.isAfter(days.getFirst())) ? d : days.getFirst();
        while (remaining > 0) {
            cursor = cursor.minusDays(1);
            if (isWeekday(cursor)) remaining--;
        }
        return cursor;
    }

    /** The {@code n}-th real trading day after {@code d}; empty when the calendar does not reach it yet. */
    public Optional<LocalDate> plus(LocalDate d, int n) {
        if (n <= 0) return Optional.of(d);
        int target = countUpTo(d) + n - 1;
        return target < days.size() ? Optional.of(days.get(target)) : Optional.empty();
    }

    private int countUpTo(LocalDate d) {
        int i = Collections.binarySearch(days, d);
        return i >= 0 ? i + 1 : -(i + 1);
    }

    private int countBefore(LocalDate d) {
        int i = Collections.binarySearch(days, d);
        return i >= 0 ? i : -(i + 1);
    }

    static int weekdaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate c = from.plusDays(1); !c.isAfter(to); c = c.plusDays(1)) {
            if (isWeekday(c)) n++;
        }
        return n;
    }

    static boolean isWeekday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }
}
