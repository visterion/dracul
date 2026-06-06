package de.visterion.dracul.voievod;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.Period;
import static org.assertj.core.api.Assertions.assertThat;

class HorizonsTest {

    @Test
    void parsesUnits() {
        assertThat(Horizons.parse("1d")).isEqualTo(Period.ofDays(1));
        assertThat(Horizons.parse("2w")).isEqualTo(Period.ofWeeks(2));
        assertThat(Horizons.parse("3m")).isEqualTo(Period.ofMonths(3));
        assertThat(Horizons.parse("12m")).isEqualTo(Period.ofMonths(12));
        assertThat(Horizons.parse("1y")).isEqualTo(Period.ofYears(1));
    }

    @Test
    void openWhenWithinWindowClosedWhenPast() {
        LocalDate today = LocalDate.of(2026, 6, 6);
        assertThat(Horizons.isOpen("2026-05-20T00:00:00Z", "1m", today)).isTrue();
        assertThat(Horizons.isOpen("2026-01-01 00:00:00+00", "1m", today)).isFalse();
        assertThat(Horizons.isOpen("2026-05-06T12:00:00Z", "1m", today)).isTrue();
    }

    @Test
    void unparseableTreatedAsOpen() {
        LocalDate today = LocalDate.of(2026, 6, 6);
        assertThat(Horizons.isOpen("2026-05-20T00:00:00Z", "", today)).isTrue();
        assertThat(Horizons.isOpen("2026-05-20T00:00:00Z", "garbage", today)).isTrue();
        assertThat(Horizons.isOpen("not-a-date", "1m", today)).isTrue();
    }

    @Test
    void approxDaysAndLongest() {
        assertThat(Horizons.approxDays("1d")).isEqualTo(1);
        assertThat(Horizons.approxDays("3m")).isEqualTo(90);
        assertThat(Horizons.longest(java.util.List.of("1m", "6m", "3m"))).isEqualTo("6m");
        assertThat(Horizons.longest(java.util.List.of())).isEqualTo("3m");
    }
}
