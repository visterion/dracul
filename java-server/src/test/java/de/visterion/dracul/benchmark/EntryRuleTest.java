package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec §2.7 / §6 test 7: before the per-currency open cutoff (USD/EUR 07:00 UTC, HKD 01:30 UTC)
 *  T's own date counts, otherwise the next trading day. */
class EntryRuleTest {

    private final List<OhlcBar> bars = BenchmarkFixtures.weekdayBars(d(2026, 3, 2), d(2026, 3, 6), "10", Map.of());

    @Test
    void a0500UtcReferenceEntersAtTheSameDaysOpen() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T05:00:00Z"), "USD")).isZero();
    }

    @Test
    void a2200UtcReferenceEntersTheNextTradingDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T22:00:00Z"), "USD")).isEqualTo(1);
    }

    @Test
    void exactly0700IsAlreadyTooLateForTheSameDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T07:00:00Z"), "USD")).isEqualTo(1);
    }

    @Test
    void aWeekendReferenceEntersMonday() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-01T22:00:00Z"), "USD")).isZero();
    }

    @Test
    void noBarAfterTheReferenceIsMinusOne() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-06T22:00:00Z"), "USD")).isEqualTo(-1);
        assertThat(EntryRule.firstEligibleDate(Instant.parse("2026-03-06T22:00:00Z"), "USD")).isEqualTo(d(2026, 3, 7));
    }

    @Test
    void eurUsesThe0700CutoffToo() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T06:59:00Z"), "EUR")).isZero();
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T07:00:00Z"), "EUR")).isEqualTo(1);
    }

    /** Final review M1: Hong Kong opens 01:30 UTC — a 01:00 UTC reference still catches that open. */
    @Test
    void anHkdReferenceAt0100UtcEntersTheSameDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T01:00:00Z"), "HKD")).isZero();
    }

    /** Final review M1: at 05:00 UTC Hong Kong has been trading for 3.5 h — entering at that
     *  day's open would be look-ahead, so the next trading day is the entry. */
    @Test
    void anHkdReferenceAt0500UtcEntersTheNextTradingDay() {
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T05:00:00Z"), "HKD")).isEqualTo(1);
        assertThat(EntryRule.entryIndex(bars, Instant.parse("2026-03-02T01:30:00Z"), "HKD")).isEqualTo(1);
    }
}
