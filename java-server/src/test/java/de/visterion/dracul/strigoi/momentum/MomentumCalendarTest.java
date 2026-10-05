package de.visterion.dracul.strigoi.momentum;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-04 §5.5 calendar table (synthetic dates, UTC). */
class MomentumCalendarTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);

    private static MomentumCalendar.Decision decide(String day, YearMonth start, Set<YearMonth> done) {
        return MomentumCalendar.decide(LocalDate.parse(day), start, 3, done::contains);
    }

    @Test
    void lastWeekdayOnAFridayWhenTheMonthEndsOnASaturday() {
        assertThat(MomentumCalendar.lastWeekday(OCT)).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(decide("2026-10-30", OCT, Set.of())).isEqualTo(new MomentumCalendar.Decision(true, OCT, null));
        assertThat(decide("2026-10-29", OCT, Set.of()).due()).isFalse();
        assertThat(decide("2026-10-31", OCT, Set.of()).due()).isFalse();   // Saturday
    }

    @Test
    void monthEndingOnAWeekdayRebalancesOnItsLastDay() {
        assertThat(decide("2026-11-30", OCT, Set.of(OCT)).target()).isEqualTo(NOV);
        assertThat(decide("2026-11-30", OCT, Set.of(OCT)).due()).isTrue();
    }

    @Test
    void catchUpOnWeekdaysOneToThreeAcrossDecemberToJanuaryAndNoRepeatAfterwards() {
        assertThat(decide("2027-01-01", OCT, Set.of()).target()).isEqualTo(DEC);   // weekday 1
        assertThat(decide("2027-01-04", OCT, Set.of()).due()).isTrue();             // weekday 2
        assertThat(decide("2027-01-05", OCT, Set.of()).due()).isTrue();             // weekday 3
        assertThat(decide("2027-01-06", OCT, Set.of()).due()).isFalse();            // weekday 4
        assertThat(decide("2027-01-04", OCT, Set.of(DEC)).due()).isFalse();         // already done
        assertThat(decide("2027-01-04", OCT, Set.of(DEC)).reason()).contains("already rebalanced");
    }

    @Test
    void theFirstWeekdayAfterACompletedMonthIsNotDue() {
        assertThat(decide("2026-11-02", OCT, Set.of(OCT)).due()).isFalse();
    }

    @Test
    void missedOnWeekdayFourOnly() {
        assertThat(MomentumCalendar.missedMonth(LocalDate.parse("2027-01-06"), OCT, 3, Set.<YearMonth>of()::contains))
                .contains(DEC);
        assertThat(MomentumCalendar.missedMonth(LocalDate.parse("2027-01-05"), OCT, 3, Set.<YearMonth>of()::contains))
                .isEmpty();
        assertThat(MomentumCalendar.missedMonth(LocalDate.parse("2027-01-07"), OCT, 3, Set.<YearMonth>of()::contains))
                .isEmpty();
        assertThat(MomentumCalendar.missedMonth(LocalDate.parse("2027-01-06"), OCT, 3, Set.of(DEC)::contains))
                .isEmpty();
    }

    /** Enabling mid-month never back-fires: start month NOV makes the October catch-up not due
     *  and never MISSED. */
    @Test
    void firstEnableMidMonth() {
        MomentumCalendar.Decision d = decide("2026-11-03", NOV, Set.of());
        assertThat(d.due()).isFalse();
        assertThat(d.target()).isEqualTo(OCT);
        assertThat(d.reason()).contains("before the start month");
        assertThat(MomentumCalendar.missedMonth(LocalDate.parse("2026-11-05"), NOV, 3, Set.<YearMonth>of()::contains))
                .isEmpty();
        assertThat(decide("2026-11-30", NOV, Set.of()).due()).isTrue();
    }

    @Test
    void weekdayIndexCountsWeekdaysOnly() {
        assertThat(MomentumCalendar.weekdayIndex(LocalDate.parse("2027-01-01"))).isEqualTo(1);
        assertThat(MomentumCalendar.weekdayIndex(LocalDate.parse("2027-01-04"))).isEqualTo(2);
        assertThat(MomentumCalendar.weekdayIndex(LocalDate.parse("2027-01-02"))).isZero();   // Saturday
    }
}
