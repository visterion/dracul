package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.RecommendationTrend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionsProxyTest {

    private final RevisionsProxy proxy = new RevisionsProxy();

    @Test void proxyIsLatestNetMinusPreviousNet() {
        // latest net = 10+5-1-0 = 14; previous net = 8+5-2-1 = 10 -> proxy 4, up
        EarningsRevisions r = proxy.revisions(List.of(
                new RecommendationTrend("2026-06-01", 10, 5, 3, 1, 0),
                new RecommendationTrend("2026-05-01", 8, 5, 4, 2, 1)));
        assertThat(r.available()).isTrue();
        assertThat(r.netProxy()).isEqualTo(4);
        assertThat(r.direction()).isEqualTo("up");
    }

    @Test void singlePeriodIsFlatZero() {
        EarningsRevisions r = proxy.revisions(List.of(
                new RecommendationTrend("2026-06-01", 10, 5, 3, 1, 0)));
        assertThat(r.netProxy()).isEqualTo(0);
        assertThat(r.direction()).isEqualTo("flat");
    }

    @Test void emptyTrendIsUnavailable() {
        assertThat(proxy.revisions(List.of()).available()).isFalse();
    }
}
