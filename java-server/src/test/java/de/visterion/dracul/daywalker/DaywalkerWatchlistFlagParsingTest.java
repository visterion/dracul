package de.visterion.dracul.daywalker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DaywalkerWatchlistFlagParsingTest {

    @Test
    void onlyCleanTrueEnablesLegacy() {
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("true")).isTrue();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("TRUE")).isTrue();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("  true  ")).isTrue();
    }

    @Test
    void everyNonTrueValueFailsSafeToDepotOnly() {
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("false")).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("FALSE")).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("")).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled(null)).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("yes")).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("1")).isFalse();
        assertThat(DaywalkerEventEngine.parseWatchlistEnabled("enabled")).isFalse();
    }
}
