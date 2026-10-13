package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec §2.7 / §6 test 7: before 07:00 UTC T's own date counts, otherwise the next trading day. */
class EntryRuleTest {

    private final List<OhlcBar> bars = BenchmarkFixtures.weekdayBars(d(2026, 3, 2), d(2026, 3, 6), "10", Map.of());

    @Test
    void a0500UtcReferenceEntersAtTheSameDaysOpen() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T05:00:00Z"))).isZero();
    }

    @Test
    void a2200UtcReferenceEntersTheNextTradingDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T22:00:00Z"))).isEqualTo(1);
    }

    @Test
    void exactly0700IsAlreadyTooLateForTheSameDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T07:00:00Z"))).isEqualTo(1);
    }

    @Test
    void aWeekendReferenceEntersMonday() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-01T22:00:00Z"))).isZero();
    }

    @Test
    void noBarAfterTheReferenceIsMinusOne() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-06T22:00:00Z"))).isEqualTo(-1);
        assertThat(EntryRule.firstEligibleDate(Instant.parse("2026-03-06T22:00:00Z"))).isEqualTo(d(2026, 3, 7));
    }
}
