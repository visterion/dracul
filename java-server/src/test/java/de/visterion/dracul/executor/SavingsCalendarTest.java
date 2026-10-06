package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-06 §3.3, §4.1, §5.3, §12. November 2026: Sun 1, Mon 2 = weekday 1. */
class SavingsCalendarTest {

    private static final LocalTime START = LocalTime.of(21, 15);

    @Test
    void planDayIsWeekdayOneCatchUpTwoAndThreeMissedFour() {
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 1), 3)).isEqualTo(SavingsCalendar.Phase.NONE);
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 2), 3)).isEqualTo(SavingsCalendar.Phase.PLAN_DAY);
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 3), 3)).isEqualTo(SavingsCalendar.Phase.CATCH_UP);
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 4), 3)).isEqualTo(SavingsCalendar.Phase.CATCH_UP);
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 5), 3)).isEqualTo(SavingsCalendar.Phase.MISSED);
        assertThat(SavingsCalendar.phase(LocalDate.of(2026, 11, 6), 3)).isEqualTo(SavingsCalendar.Phase.NONE);
        assertThat(SavingsCalendar.month(LocalDate.of(2026, 11, 2))).isEqualTo("2026-11");
    }

    @Test
    void windowBoundaries() {
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-02T21:14:59Z"), START)).isFalse();
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-02T21:15:00Z"), START)).isTrue();
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-02T23:59:59Z"), START)).isTrue();
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-02T15:00:00Z"), START)).isFalse();
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-03T00:00:00Z"), START)).isFalse();
        assertThat(SavingsCalendar.inWindow(Instant.parse("2026-11-07T22:00:00Z"), START))
                .as("Saturday").isFalse();
    }

    @Test
    void sessionGateUsesTheNewYorkTradeDate() {
        Instant add = Instant.parse("2026-11-02T23:00:00Z");                 // Mon 18:00 New York
        assertThat(SavingsCalendar.sessionPassed(add, Instant.parse("2026-11-02T23:05:00Z")))
                .as("a 23:05 operator run the same evening sees no session").isFalse();
        assertThat(SavingsCalendar.sessionPassed(add, Instant.parse("2026-11-03T23:00:00Z"))).isTrue();
        assertThat(SavingsCalendar.nyTradeDate(Instant.parse("2026-11-03T03:00:00Z")))
                .as("03:00 UTC is still the previous New York day").isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void aFridayAddIsNotStaleBeforeTuesday() {
        Instant friday = Instant.parse("2026-11-06T23:00:00Z");
        assertThat(SavingsCalendar.stale(friday, Instant.parse("2026-11-09T23:00:00Z"))).isFalse(); // Mon
        assertThat(SavingsCalendar.stale(friday, Instant.parse("2026-11-10T23:00:00Z"))).isTrue();  // Tue
        assertThat(SavingsCalendar.weekdaysAfter(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 8))).isZero();
        assertThat(SavingsCalendar.firstWeekdayAfter(LocalDate.of(2026, 11, 6))).isEqualTo(LocalDate.of(2026, 11, 9));
    }
}
