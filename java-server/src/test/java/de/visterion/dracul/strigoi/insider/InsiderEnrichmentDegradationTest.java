package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 2026-08-06 insider regression: two per-ITEM errors were read as two SOURCE outages, which
 * crossed {@code skipAll()} and switched enrichment off for the whole run — while the fetch still
 * reported {@code items=1 (partial=false truncated=false status=healthy)}.
 *
 * <p>Both messages are reproduced verbatim from that run. Neither describes an outage: a Yahoo 404
 * is Yahoo saying it does not know that symbol, and a missing CIK is one issuer that will not
 * resolve.
 */
class InsiderEnrichmentDegradationTest {

    private static final String YAHOO_404 =
            "Agora tool error: Yahoo Finance OHLC returned HTTP 404 NOT_FOUND";
    private static final String NO_CIK = "Agora tool error: no CIK for N/A";

    private static final List<RecommendationTrend> TREND =
            List.of(new RecommendationTrend("2026-07-01", 1, 2, 1, 0, 0));

    private static InsiderCluster cluster(String ticker) {
        return new InsiderCluster(ticker, ticker + " Inc.",
                List.of(InsiderFiler.unclassified("Alice", "Chief Executive Officer")),
                LocalDate.now().minusDays(10), LocalDate.now().minusDays(2),
                BigDecimal.valueOf(1_800_000), BigDecimal.valueOf(90_000),
                1, BigDecimal.valueOf(1_400_000));
    }

    /** 25 usable bars this calendar year, so ADV and YTD are both computable. */
    private static List<OhlcBar> bars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(24);
        for (int i = 0; i < 25; i++) {
            BigDecimal c = BigDecimal.TEN;
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1_000_000L));
        }
        return out;
    }

    /** Exactly what production throws for an OHLC tool error: AgoraMarketData re-wraps the Agora
     *  exception in a MarketDataException(UNAVAILABLE) and keeps it as the cause. */
    private static MarketDataException wrappedOhlc(String message) {
        return new MarketDataException(MarketDataException.Kind.UNAVAILABLE, message,
                new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, message, null));
    }

    private static AgoraMarketData ohlcFailingFor(String failingSymbol, String message) {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                if (failingSymbol.equals(symbol)) throw wrappedOhlc(message);
                return bars();
            }
        };
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgoraCompanyData companyData() {
        AgoraCompanyData m = mock(AgoraCompanyData.class);
        when(m.recommendationsStrict(anyString())).thenReturn(TREND);
        JsonNode node = MAPPER.readTree("{\"marketCapitalization\":850.0}");
        when(m.fundamentalsStrict(anyString())).thenReturn(node);
        return m;
    }

    private static AgoraEarnings earnings() {
        AgoraEarnings m = mock(AgoraEarnings.class);
        when(m.nextEarningsDate(anyString())).thenReturn(Optional.empty());
        return m;
    }

    private static Form4OwnerHistory emptyHistory() {
        return new Form4OwnerHistory("CIK", null, null, List.of(), false);
    }

    private static InsiderEnrichmentService service(AgoraMarketData md, AgoraFilings filings) {
        return new InsiderEnrichmentService(md, companyData(), earnings(),
                filings, new RoutineClassifier());
    }

    @Test
    void singleYahoo404DoesNotDisableOhlcForTheRemainingClusters() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict(anyString())).thenReturn(emptyHistory());

        var batch = service(ohlcFailingFor("SYNA", YAHOO_404), filings)
                .enrich(List.of(cluster("SYNA"), cluster("SYNB")));

        var a = batch.clusters().stream().filter(c -> c.ticker().equals("SYNA")).findFirst().orElseThrow();
        var b = batch.clusters().stream().filter(c -> c.ticker().equals("SYNB")).findFirst().orElseThrow();
        assertThat(a.adv()).isNull();               // the 404 degrades ITS cluster
        assertThat(b.adv()).isNotNull();            // and only its cluster
        assertThat(b.ytdReturnAvailable()).isTrue();
    }

    @Test
    void singleMissingCikDoesNotDisableOwnerHistoryForTheRemainingClusters() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict("SYNA")).thenThrow(
                new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, NO_CIK, null));
        when(filings.ownerHistoryStrict("SYNB")).thenReturn(emptyHistory());

        AgoraMarketData md = ohlcFailingFor("NONE", YAHOO_404);
        var batch = service(md, filings).enrich(List.of(cluster("SYNA"), cluster("SYNB")));

        // SYNB is still queried — the old guard skipped it outright
        verify(filings, times(1)).ownerHistoryStrict("SYNB");
        var a = batch.clusters().stream().filter(c -> c.ticker().equals("SYNA")).findFirst().orElseThrow();
        var b = batch.clusters().stream().filter(c -> c.ticker().equals("SYNB")).findFirst().orElseThrow();
        assertThat(a.classificationAvailable()).isFalse();
        assertThat(b.classificationAvailable()).isTrue();
    }

    @Test
    void theProductionPairNoLongerSkipsEnrichmentForTheRestOfTheBatch() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict("SYNA")).thenThrow(
                new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, NO_CIK, null));
        when(filings.ownerHistoryStrict("SYNB")).thenReturn(emptyHistory());

        var batch = service(ohlcFailingFor("SYNA", YAHOO_404), filings)
                .enrich(List.of(cluster("SYNA"), cluster("SYNB")));

        // two sources failed once each on cluster 1; cluster 2 is still fully enriched
        var b = batch.clusters().stream().filter(c -> c.ticker().equals("SYNB")).findFirst().orElseThrow();
        assertThat(b.metricsAvailable()).isTrue();
        assertThat(b.adv()).isNotNull();
        assertThat(b.coverageAvailable()).isTrue();
        assertThat(b.classificationAvailable()).isTrue();
    }

    @Test
    void aPerItemSkipIsCountedAndReachesTheFetchHealth() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict(anyString())).thenReturn(emptyHistory());

        var batch = service(ohlcFailingFor("SYNA", YAHOO_404), filings)
                .enrich(List.of(cluster("SYNA"), cluster("SYNB")));

        assertThat(batch.degradedClusters()).isEqualTo(1);
        assertThat(batch.truncated()).isFalse();

        var health = StrigoiInsiderWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora-edgar"), batch);
        assertThat(health.partial()).isTrue();
        assertThat(health.truncated()).isFalse();
        assertThat(health.status()).isEqualTo("healthy");
        assertThat(health.detail()).contains("1 cluster(s) lost at least one enrichment source");
    }

    @Test
    void aCleanBatchLeavesTheFetchHealthUntouched() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict(anyString())).thenReturn(emptyHistory());

        var batch = service(ohlcFailingFor("NONE", YAHOO_404), filings)
                .enrich(List.of(cluster("SYNA"), cluster("SYNB")));

        assertThat(batch.degraded()).isFalse();
        var base = DataSourceHealth.healthy("agora-edgar");
        assertThat(StrigoiInsiderWebhookController.mergeHealth(base, batch)).isSameAs(base);
    }

    @Test
    void aRunOfPerItemErrorsStillDeclaresTheSourceDown() {
        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.ownerHistoryStrict(anyString())).thenReturn(emptyHistory());
        AgoraMarketData md = new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                throw wrappedOhlc(YAHOO_404);
            }
        };

        var batch = service(md, filings).enrich(
                List.of(cluster("SYNA"), cluster("SYNB"), cluster("SYNC"), cluster("SYND")));

        // every cluster loses OHLC, and every cluster is counted
        assertThat(batch.clusters()).allSatisfy(c -> assertThat(c.adv()).isNull());
        assertThat(batch.degradedClusters()).isEqualTo(4);
        // the other sources keep serving: one down source is below skipAll()
        assertThat(batch.clusters()).allSatisfy(c -> assertThat(c.metricsAvailable()).isTrue());
    }
}
