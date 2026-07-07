package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.RecommendationTrend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystCoverageTest {

    @Test void coverageIsSumOfLatestPeriod() {
        var cov = AnalystCoverage.of(List.of(
                new RecommendationTrend("2026-06-01", 3, 5, 4, 2, 1),   // latest -> 15
                new RecommendationTrend("2026-05-01", 1, 1, 1, 1, 1)));
        assertThat(cov.available()).isTrue();
        assertThat(cov.coverage()).isEqualTo(15);
    }

    @Test void singlePeriodWorks() {
        var cov = AnalystCoverage.of(List.of(new RecommendationTrend("2026-06-01", 1, 0, 0, 0, 0)));
        assertThat(cov.available()).isTrue();
        assertThat(cov.coverage()).isEqualTo(1);
    }

    @Test void emptyTrendIsUnavailable() {
        var cov = AnalystCoverage.of(List.of());
        assertThat(cov.available()).isFalse();
        assertThat(cov.coverage()).isNull();
    }
}
