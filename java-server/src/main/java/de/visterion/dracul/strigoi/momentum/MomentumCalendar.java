package de.visterion.dracul.strigoi.momentum;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * When a rebalance is due (spec 2026-10-04 §5.5), pure. The target month of a run is M on the
 * last weekday (Mon–Fri, UTC) of M, and M on weekdays 1..catch-up of M+1. Due ⇔ there is a
 * target, it is not before the start month, and it has no completed rebalance. MISSED is
 * reported on weekday catch-up+1 for a previous month (≥ start) without a completed rebalance.
 * US holidays are not modelled: Agora's completed-bar rule makes a holiday run rank on the
 * previous session.
 */
public final class MomentumCalendar {

    private MomentumCalendar() {}

    /** @param target the month this run would rebalance (null: no rebalance day); @param reason
     *  why it is not due (null when due) */
    public record Decision(boolean due, YearMonth target, String reason) {}

    public static boolean weekday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }

    public static LocalDate lastWeekday(YearMonth m) {
        LocalDate d = m.atEndOfMonth();
        while (!weekday(d)) d = d.minusDays(1);
        return d;
    }

    /** 1-based index of {@code d} among its month's weekdays; 0 on a weekend. */
    public static int weekdayIndex(LocalDate d) {
        if (!weekday(d)) return 0;
        int n = 0;
        for (LocalDate x = d.withDayOfMonth(1); !x.isAfter(d); x = x.plusDays(1)) {
            if (weekday(x)) n++;
        }
        return n;
    }

    public static Optional<YearMonth> targetMonth(LocalDate today, int catchUpWeekdays) {
        if (!weekday(today)) return Optional.empty();
        YearMonth m = YearMonth.from(today);
        if (today.equals(lastWeekday(m))) return Optional.of(m);
        if (weekdayIndex(today) <= catchUpWeekdays) return Optional.of(m.minusMonths(1));
        return Optional.empty();
    }

    public static Decision decide(LocalDate today, YearMonth startMonth, int catchUpWeekdays,
            Predicate<YearMonth> completed) {
        Optional<YearMonth> t = targetMonth(today, catchUpWeekdays);
        if (t.isEmpty()) {
            return new Decision(false, null, "no rebalance day: neither the last weekday of the "
                    + "month nor one of its first " + catchUpWeekdays + " weekdays (catch-up)");
        }
        YearMonth target = t.get();
        if (target.isBefore(startMonth)) {
            return new Decision(false, target, "target month " + target
                    + " is before the start month " + startMonth);
        }
        if (completed.test(target)) {
            return new Decision(false, target, "target month " + target + " already rebalanced");
        }
        return new Decision(true, target, null);
    }

    public static Optional<YearMonth> missedMonth(LocalDate today, YearMonth startMonth,
            int catchUpWeekdays, Predicate<YearMonth> completed) {
        if (weekdayIndex(today) != catchUpWeekdays + 1) return Optional.empty();
        YearMonth previous = YearMonth.from(today).minusMonths(1);
        if (previous.isBefore(startMonth) || completed.test(previous)) return Optional.empty();
        return Optional.of(previous);
    }
}
