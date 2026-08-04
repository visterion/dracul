package de.visterion.dracul.strigoi.lazarus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard for a claim that was wrong in production for as long as it existed: every javadoc
 * and config comment on the Lazarus pre-filter path said the 52-week-range probe was
 * "Yahoo-routed". It is not, and never was on this configuration — Agora's {@code ohlc} chain
 * orders Alpaca (@Order 5) ahead of Saxo (7), TwelveData (10), Finnhub (20) and Yahoo (30), and
 * Alpaca is credential-configured on production. Measured 2026-08-04: 616 of the pre-filter's
 * daily-bar fetches went to {@code data.alpaca.markets}.
 *
 * <p>A wrong provider attribution is not cosmetic here: it is what an operator reads when the
 * pre-filter degrades, and it sends them to the wrong provider's rate limit and the wrong
 * dashboard. The prose may name the chain or name Alpaca; it may not name Yahoo as the route.
 */
class LazarusProviderClaimTest {

    /** Files that describe the pre-filter / 52-week-range probe path. */
    private static final List<String> PROBE_PATH_FILES = List.of(
            "src/main/java/de/visterion/dracul/hunting/agora/AgoraPriceRange.java",
            "src/main/java/de/visterion/dracul/strigoi/lazarus/LazarusUniverseService.java",
            "src/main/java/de/visterion/dracul/strigoi/lazarus/StrigoiLazarusWebhookController.java",
            "src/main/resources/application.yaml");

    @Test
    void noProbePathFileClaimsTheRouteIsYahoo() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String file : PROBE_PATH_FILES) {
            Path path = Path.of(file);
            assertThat(path).as("probe-path file %s exists", file).exists();
            String[] lines = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("Yahoo-routed") || lines[i].contains("Yahoo's 252-bar")) {
                    offenders.add(file + ":" + (i + 1) + " " + lines[i].strip());
                }
            }
        }
        assertThat(offenders)
                .as("the pre-filter probes are served by Agora's provider chain (Alpaca first), "
                        + "not by Yahoo — these lines still claim otherwise")
                .isEmpty();
    }
}
