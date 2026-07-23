package de.visterion.dracul.daywalker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the T1.7 session-window defaults (spec §A2/§5) in BOTH places that carry a
 * default: application.yaml (cron + duration + poll budget) and the hardcoded
 * {@code @Value} duration fallback in DaywalkerDefaults. The cron @Value deliberately
 * has NO hardcoded fallback — this test also pins its absence.
 */
class DaywalkerConfigDefaultsPinTest {

	@Test
	void yamlCarriesTheSixteenHourSessionDefaults() throws Exception {
		String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
		assertThat(yaml).contains("${DRACUL_DAYWALKER_SESSION_CRON:0 0 8 * * 1-5}");
		assertThat(yaml).contains("${DRACUL_DAYWALKER_SESSION_DURATION:57600}");
		assertThat(yaml).contains("${DRACUL_DAYWALKER_POLL_BUDGET_MS:60000}");
	}

	@Test
	void defaultsClassCarriesTheDurationFallbackAndNoCronFallback() throws Exception {
		String src = Files.readString(Path.of(
				"src/main/java/de/visterion/dracul/daywalker/DaywalkerDefaults.java"));
		assertThat(src).contains("session-duration:57600");
		assertThat(src).doesNotContain("session-duration:23400");
		assertThat(src).contains("${dracul.daywalker.session-cron}");
		assertThat(src).doesNotContain("session-cron:");
	}

	@Test
	void yamlCarriesTheT22MacroAndSectorDefaults() throws Exception {
		String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
		assertThat(yaml).contains("${DRACUL_DAYWALKER_MACRO_COOLDOWN:28800}");
		assertThat(yaml).contains("${DRACUL_SECTOR_TTL_SECONDS:86400}");
		assertThat(yaml).contains("${DRACUL_SECTOR_NEGATIVE_TTL_SECONDS:3600}");
	}

	@Test
	void yamlCarriesTheWatchlistEnabledDefault() throws Exception {
		String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
		assertThat(yaml).contains("${DRACUL_DAYWALKER_WATCHLIST_ENABLED:false}");
	}
}
