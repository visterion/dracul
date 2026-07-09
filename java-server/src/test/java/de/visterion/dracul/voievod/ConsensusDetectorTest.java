package de.visterion.dracul.voievod;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 6);
    private final ConsensusDetector detector = new ConsensusDetector();

    private Prey prey(String symbol, String company, String discoveredBy, String discoveredAt, String horizon) {
        return new Prey("id-" + symbol + "-" + discoveredBy, symbol, company, "ANOM",
                0.7, "thesis", List.of(), List.of(), List.of(), horizon, discoveredBy, discoveredAt);
    }

    @Test
    void twoDistinctStrigoiSameSymbolFormCluster() {
        var clusters = detector.detect(List.of(
                prey("AVGO", "Broadcom", "strigoi-spin", "2026-05-20T00:00:00Z", "6m"),
                prey("AVGO", "Broadcom Inc", "strigoi-insider", "2026-06-01T00:00:00Z", "3m")
        ), TODAY);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).symbol()).isEqualTo("AVGO");
        assertThat(clusters.get(0).prey()).hasSize(2);
        assertThat(clusters.get(0).companyName()).isEqualTo("Broadcom Inc");
    }

    @Test
    void sameStrigoiTwiceIsNotConsensus() {
        var clusters = detector.detect(List.of(
                prey("AVGO", "Broadcom", "strigoi-spin", "2026-05-20T00:00:00Z", "6m"),
                prey("AVGO", "Broadcom", "strigoi-spin", "2026-06-01T00:00:00Z", "6m")
        ), TODAY);
        assertThat(clusters).isEmpty();
    }

    @Test
    void expiredPreyExcludedAndCanDropClusterBelowThreshold() {
        var clusters = detector.detect(List.of(
                prey("AVGO", "Broadcom", "strigoi-spin", "2026-05-20T00:00:00Z", "6m"),
                prey("AVGO", "Broadcom", "strigoi-insider", "2026-01-01T00:00:00Z", "1m")
        ), TODAY);
        assertThat(clusters).isEmpty();
    }

    @Test
    void symbolCaseIsFolded() {
        var clusters = detector.detect(List.of(
                prey("avgo", "Broadcom", "strigoi-spin", "2026-05-20T00:00:00Z", "6m"),
                prey("AVGO", "Broadcom", "strigoi-insider", "2026-06-01T00:00:00Z", "3m")
        ), TODAY);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).symbol()).isEqualTo("AVGO");
    }

    @Test
    void emptyInEmptyOut() {
        assertThat(detector.detect(List.of(), TODAY)).isEmpty();
    }
}
