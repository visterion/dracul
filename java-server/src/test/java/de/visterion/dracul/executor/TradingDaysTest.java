package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The one trading-day counter shared by veto #3 (EntryContextAssembler) and PendingSignalSweeper.
 *  A string literal cannot exercise the JVM-zone coupling of ExecutorSignal.createdAt — that case
 *  lives in ExecutorSignalRepositoryIT. */
class TradingDaysTest {

    /** Wednesday 2026-09-16, the same fixed instant PendingSignalSweeperTest uses. */
    private static final Clock WEDNESDAY =
            Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void tuesdayIsSixTradingDaysBeforeTheFollowingWednesday() {
        // 09-09, 09-10, 09-11, 09-14, 09-15, 09-16 -> 6 (weekend 09-12/09-13 not counted)
        assertThat(TradingDays.ageOf("2026-09-08", WEDNESDAY)).isEqualTo(6L);
    }

    @Test
    void wednesdayIsFiveTradingDaysBeforeTheFollowingWednesday() {
        assertThat(TradingDays.ageOf("2026-09-09", WEDNESDAY)).isEqualTo(5L);
    }

    @Test
    void weekendIsNotCounted() {
        // Friday 09-11 -> 09-14, 09-15, 09-16 = 3
        assertThat(TradingDays.ageOf("2026-09-11", WEDNESDAY)).isEqualTo(3L);
    }

    @Test
    void nullBlankAndGarbageYieldMinusOne() {
        assertThat(TradingDays.ageOf(null, WEDNESDAY)).isEqualTo(-1L);
        assertThat(TradingDays.ageOf("   ", WEDNESDAY)).isEqualTo(-1L);
        assertThat(TradingDays.ageOf("not-a-date", WEDNESDAY)).isEqualTo(-1L);
    }

    @Test
    void theProdTimestampShapeIsReadAsItsDatePart() {
        // ExecutorSignal.createdAt is Timestamp.toString() of a TIMESTAMPTZ column.
        assertThat(TradingDays.ageOf("2026-09-08 04:01:36.733458+00", WEDNESDAY)).isEqualTo(6L);
    }

    @Test
    void anIsoInstantShapeIsAlsoReadAsItsDatePart() {
        assertThat(TradingDays.ageOf("2026-09-08T04:01:36Z", WEDNESDAY)).isEqualTo(6L);
    }

    @Test
    void aSignalCreatedTodayIsZeroTradingDaysOld() {
        assertThat(TradingDays.ageOf("2026-09-16", WEDNESDAY)).isZero();
    }
}
