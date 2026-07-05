package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.finnhub.RecommendationTrend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevisionsProxyTest {

    private static AgoraCompanyData companyData(List<RecommendationTrend> trend) {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.recommendations("ACME")).thenReturn(trend);
        return d;
    }

    @Test void proxyIsLatestNetMinusPreviousNet() {
        // latest net = 10+5-1-0 = 14; previous net = 8+5-2-1 = 10 -> proxy 4, up
        var proxy = new RevisionsProxy(companyData(List.of(
                new RecommendationTrend("2026-06-01", 10, 5, 3, 1, 0),
                new RecommendationTrend("2026-05-01", 8, 5, 4, 2, 1))));
        EarningsRevisions r = proxy.revisions("ACME");
        assertThat(r.available()).isTrue();
        assertThat(r.netProxy()).isEqualTo(4);
        assertThat(r.direction()).isEqualTo("up");
    }

    @Test void singlePeriodIsFlatZero() {
        var proxy = new RevisionsProxy(companyData(List.of(
                new RecommendationTrend("2026-06-01", 10, 5, 3, 1, 0))));
        EarningsRevisions r = proxy.revisions("ACME");
        assertThat(r.netProxy()).isEqualTo(0);
        assertThat(r.direction()).isEqualTo("flat");
    }

    @Test void emptyTrendIsUnavailable() {
        assertThat(new RevisionsProxy(companyData(List.of())).revisions("ACME").available()).isFalse();
    }
}
