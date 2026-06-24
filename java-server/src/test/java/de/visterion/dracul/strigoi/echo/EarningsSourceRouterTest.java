package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsSourceRouterTest {

    private static PeadEarningsSource src(String id, DataSourceResult<EarningsObservation> r) {
        return new PeadEarningsSource() {
            @Override public DataSourceResult<EarningsObservation> recent(LocalDate a, LocalDate b) { return r; }
            @Override public String id() { return id; }
        };
    }
    private static EarningsObservation obs(String s) {
        return new EarningsObservation(s, s, LocalDate.of(2026,5,1), null, null, null, null, null);
    }

    @Test
    void usesPrimaryWhenHealthy() {
        var primary = src("finnhub", DataSourceResult.healthy("finnhub", List.of(obs("AAPL"))));
        var fallback = src("yahoo", DataSourceResult.healthy("yahoo", List.of(obs("ZZZ"))));
        var router = new EarningsSourceRouter(List.of(primary, fallback), "finnhub");
        var out = router.recent(LocalDate.of(2026,4,25), LocalDate.of(2026,5,1));
        assertThat(out.items()).extracting(EarningsObservation::symbol).containsExactly("AAPL");
    }

    @Test
    void fallsBackWhenPrimaryUnavailable() {
        var primary = src("finnhub", DataSourceResult.unavailable("finnhub", "rate limited"));
        var fallback = src("yahoo", DataSourceResult.healthy("yahoo", List.of(obs("ZZZ"))));
        var router = new EarningsSourceRouter(List.of(primary, fallback), "finnhub");
        var out = router.recent(LocalDate.of(2026,4,25), LocalDate.of(2026,5,1));
        assertThat(out.health().isHealthy()).isTrue();
        assertThat(out.items()).extracting(EarningsObservation::symbol).containsExactly("ZZZ");
    }

    @Test
    void returnsPrimaryResultWhenBothUnavailable() {
        var primary = src("finnhub", DataSourceResult.unavailable("finnhub", "rate limited"));
        var fallback = src("yahoo", DataSourceResult.unavailable("yahoo", "blocked"));
        var router = new EarningsSourceRouter(List.of(primary, fallback), "finnhub");
        var out = router.recent(LocalDate.of(2026,4,25), LocalDate.of(2026,5,1));
        assertThat(out.health().isHealthy()).isFalse();
    }
}
