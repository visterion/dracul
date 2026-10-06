package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsPlanSettingsTest {

    @Test
    void defaultsAreTheSpecValues() {
        SavingsPlanSettings s = SavingsPlanSettings.defaults();
        assertThat(s.enabled()).isFalse();
        assertThat(s.monthlyPct()).isEqualByComparingTo("0.02");
        assertThat(s.maxPositionPct()).isEqualByComparingTo("0.08");
        assertThat(s.basketCapPct()).isEqualByComparingTo("0.50");
        assertThat(s.limitPremiumPct()).isEqualByComparingTo("0.02");
        assertThat(s.catchUpWeekdays()).isEqualTo(3);
        assertThat(s.windowStartUtc()).isEqualTo(LocalTime.of(21, 15));
        assertThat(s.tif()).isEqualTo("gtc");
        assertThat(s.placeFirst()).isFalse();
    }

    @Test
    void configParsesTheWindowAndNormalisesTheTif() {
        SavingsPlanSettings s = new SavingsPlanConfig().savingsPlanSettings(true,
                new BigDecimal("0.02"), new BigDecimal("0.08"), new BigDecimal("0.50"),
                new BigDecimal("0.02"), 3, "21:15", " DAY ", true);
        assertThat(s.enabled()).isTrue();
        assertThat(s.windowStartUtc()).isEqualTo(LocalTime.of(21, 15));
        assertThat(s.tif()).isEqualTo("day");
        assertThat(s.placeFirst()).isTrue();
    }

    @Test
    void rejectsAnUnknownTifAndOutOfRangeFractions() {
        assertThatThrownBy(() -> new SavingsPlanSettings(false, new BigDecimal("0.02"),
                new BigDecimal("0.08"), new BigDecimal("0.50"), new BigDecimal("0.02"), 3,
                LocalTime.of(21, 15), "gtd", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tif");
        assertThatThrownBy(() -> new SavingsPlanSettings(false, BigDecimal.ZERO,
                new BigDecimal("0.08"), new BigDecimal("0.50"), new BigDecimal("0.02"), 3,
                LocalTime.of(21, 15), "gtc", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("monthly-pct");
        assertThatThrownBy(() -> new SavingsPlanSettings(false, new BigDecimal("0.02"),
                new BigDecimal("0.08"), new BigDecimal("0.50"), new BigDecimal("0.02"), 0,
                LocalTime.of(21, 15), "gtc", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catch-up-weekdays");
    }
}
