package de.visterion.dracul.executor;

import de.visterion.dracul.strigoi.momentum.MomentumCalendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * When the Tech-Sparplan may act (spec 2026-10-06 §3.3, §4.1, §5.3), pure.
 *
 * <ul>
 *   <li>Plan day = weekday index 1 of the UTC month (same calendar as momentum), catch-up on
 *       2..catchUp, MISSED on catchUp+1. No holiday calendar (§9).</li>
 *   <li>Window: UTC time in [windowStart, 24:00) on Mon–Fri — after the US close in summer
 *       (20:00) and winter (21:00).</li>
 *   <li>Session gate: the row's New York trade date ({@code created_at} in America/New_York) is
 *       strictly before today's — time-based, so a 23:05 operator run cannot consolidate a 23:00 add
 *       and a null run id is harmless.</li>
 *   <li>Stale: still PLACING/PLACED ≥ 2 weekdays after its NY trade date — counted in weekdays, not
 *       hours, so a Friday add is not stale before Tuesday.</li>
 * </ul>
 */
public final class SavingsCalendar {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public enum Phase { NONE, PLAN_DAY, CATCH_UP, MISSED }

    private SavingsCalendar() {
    }

    public static Phase phase(LocalDate todayUtc, int catchUpWeekdays) {
        int index = MomentumCalendar.weekdayIndex(todayUtc);
        if (index == 1) return Phase.PLAN_DAY;
        if (index >= 2 && index <= catchUpWeekdays) return Phase.CATCH_UP;
        if (index == catchUpWeekdays + 1) return Phase.MISSED;
        return Phase.NONE;
    }

    public static boolean inWindow(Instant now, LocalTime windowStartUtc) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        if (!MomentumCalendar.weekday(utc.toLocalDate())) return false;
        return !utc.toLocalTime().isBefore(windowStartUtc);
    }

    public static LocalDate nyTradeDate(Instant t) {
        return t.atZone(NEW_YORK).toLocalDate();
    }

    public static boolean sessionPassed(Instant createdAt, Instant now) {
        return nyTradeDate(createdAt).isBefore(nyTradeDate(now));
    }

    /** Mon–Fri days in {@code (from, to]}. */
    public static int weekdaysAfter(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (MomentumCalendar.weekday(d)) n++;
        }
        return n;
    }

    public static boolean stale(Instant createdAt, Instant now) {
        return weekdaysAfter(nyTradeDate(createdAt), nyTradeDate(now)) >= 2;
    }

    public static LocalDate firstWeekdayAfter(LocalDate d) {
        LocalDate x = d.plusDays(1);
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.plusDays(1);
        }
        return x;
    }

    public static String month(LocalDate todayUtc) {
        return String.format("%04d-%02d", todayUtc.getYear(), todayUtc.getMonthValue());
    }
}
