package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EchoEnrichmentServiceTest {

    // candidate reported 2 days ago; epsActual 1.80, revenue 1000 vs 900 (beat)
    private static PeadCandidate cand(String sym, double actual) {
        return new PeadCandidate(sym, sym + " Inc.", LocalDate.now().minusDays(2),
                BigDecimal.valueOf(actual), BigDecimal.valueOf(1.20), BigDecimal.valueOf(10.0),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(900), BigDecimal.valueOf(190));
    }

    // 8 quarters newest-first, period-ends anchored to the candidate's reportDate so the
    // date-based SUE finds a year-ago partner. EPS grows YoY with varying gaps (non-zero std).
    private static List<QuarterlyEps> historyFor(LocalDate reportDate) {
        double[] eps = {2.00, 1.85, 1.70, 1.60, 1.50, 1.40, 1.30, 1.25}; // k=0 newest
        List<QuarterlyEps> h = new ArrayList<>();
        for (int k = 0; k < eps.length; k++) {
            h.add(new QuarterlyEps(reportDate.minusDays(120L + 91L * k), BigDecimal.valueOf(eps[k])));
        }
        return h;
    }

    private EpsHistoryPort historyPort(List<QuarterlyEps> q) {
        return (symbol, max) -> q;
    }

    @Test
    void enrichesWithSueDecileRevenueSurpriseDoubleBeatAndStreak() {
        var c = cand("AAPL", 1.80);
        var svc = new EchoEnrichmentService(new SueEngine(), historyPort(historyFor(c.reportDate())));
        var out = svc.enrich(List.of(c));
        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.sueAvailable()).isTrue();
        assertThat(e.sue()).isNotNull();
        assertThat(e.sueDecile()).isBetween(1, 10);
        assertThat(e.revenueSurprisePercent()).isGreaterThan(BigDecimal.ZERO);
        assertThat(e.doubleBeat()).isTrue();
        assertThat(e.consecutiveBeats()).isEqualTo(4);
        assertThat(e.daysSinceReport()).isEqualTo(2);
    }

    @Test
    void degradesWhenNoHistory() {
        var svc = new EchoEnrichmentService(new SueEngine(), historyPort(List.of()));
        var out = svc.enrich(List.of(cand("ZZZ", 1.80)));
        assertThat(out.get(0).sueAvailable()).isFalse();
        assertThat(out.get(0).sueDecile()).isNull();
        assertThat(out.get(0).consecutiveBeats()).isNull();
    }
}
