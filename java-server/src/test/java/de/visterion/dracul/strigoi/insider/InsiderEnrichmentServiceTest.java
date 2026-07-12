package de.visterion.dracul.strigoi.insider;

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
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.junit.jupiter.api.Test;

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

class InsiderEnrichmentServiceTest {

    private static InsiderCluster cluster(String ticker) {
        return cluster(ticker, BigDecimal.valueOf(1_800_000));
    }

    private static InsiderCluster cluster(String ticker, BigDecimal totalDollarValue) {
        return new InsiderCluster(ticker, ticker + " Inc.",
                List.of(InsiderFiler.unclassified("Alice", "Chief Executive Officer"),
                        InsiderFiler.unclassified("Bob", ""),
                        InsiderFiler.unclassified("Carol", "Chief Financial Officer")),
                LocalDate.now().minusDays(10), LocalDate.now().minusDays(2),
                totalDollarValue, BigDecimal.valueOf(90_000),
                1, BigDecimal.valueOf(1_400_000));
    }

    /** InsiderEnrichmentService with the two owner-history deps defaulted: a real
     *  {@link RoutineClassifier} and an AgoraFilings that returns an EMPTY (successful) owner
     *  history, so classification is available-but-all-UNKNOWN and never trips the source-down
     *  guard — keeping the pre-classification context assertions in these tests untouched. */
    private static InsiderEnrichmentService enrichmentService(AgoraMarketData md, EquityMetricsExtractor em,
                                                              AgoraCompanyData cd, AgoraEarnings ea) {
        return new InsiderEnrichmentService(md, em, cd, ea, ownerHistoryEmpty(), new RoutineClassifier());
    }

    private static AgoraFilings ownerHistoryEmpty() {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.ownerHistoryStrict(anyString()))
                .thenReturn(new Form4OwnerHistory("", null, null, List.of(), false));
        return f;
    }

    private static AgoraFilings filingsReturning(Form4OwnerHistory h) {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.ownerHistoryStrict(anyString())).thenReturn(h);
        return f;
    }

    private static AgoraFilings ownerHistoryThrowing() {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.ownerHistoryStrict(anyString())).thenThrow(new AgoraUnavailableException("owner history down"));
        return f;
    }

    private static Form4OwnerHistory.Transaction buy(LocalDate date, long shares,
                                                     Long sharesOwnedFollowing, Boolean plan) {
        return new Form4OwnerHistory.Transaction(date, "P", "A", "4",
                BigDecimal.valueOf(shares), null, null,
                sharesOwnedFollowing == null ? null : BigDecimal.valueOf(sharesOwnedFollowing), plan);
    }

    private static Form4OwnerHistory.Owner owner(String name, Form4OwnerHistory.Transaction... txs) {
        return new Form4OwnerHistory.Owner(name, "", "", List.of(txs));
    }

    /** First bar of the calendar year closes at 20, the last 24 bars close at 10 with
     *  volume 1_000_000 -> adv = 10_000_000, ytdReturn = (10 - 20) / 20 = -0.5. */
    private static List<OhlcBar> bars() {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate jan = LocalDate.of(LocalDate.now().getYear(), 1, 2);
        out.add(new OhlcBar(jan, BigDecimal.valueOf(20), BigDecimal.valueOf(20),
                BigDecimal.valueOf(20), BigDecimal.valueOf(20), 1_000_000L));
        LocalDate start = LocalDate.now().minusDays(23);
        for (int i = 0; i < 24; i++) {
            BigDecimal c = BigDecimal.TEN;
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1_000_000L));
        }
        return out;
    }

    /** {@code count} consecutive daily bars starting at {@code start}, close 10, volume 1M. */
    private static List<OhlcBar> barsOn(LocalDate start, int count) {
        List<OhlcBar> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal c = BigDecimal.TEN;
            out.add(new OhlcBar(start.plusDays(i), c, c, c, c, 1_000_000L));
        }
        return out;
    }

    private static AgoraMarketData marketDataReturning(List<OhlcBar> b) {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) { return b; }
        };
    }

    private static AgoraMarketData marketDataThrowing() {
        return new AgoraMarketData(null) {
            @Override public MarketData resolve(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<OhlcBar> dailyOhlcHistory(String symbol, int days) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "outage");
            }
        };
    }

    private static EquityMetricsExtractor equityMetrics(Double marketCap, boolean available) {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics(anyString())).thenReturn(
                available ? new EquityMetrics(1.0, marketCap, 100.0, 200.0, "Technology", true)
                        : EquityMetrics.unavailable());
        return m;
    }

    private static AgoraCompanyData companyData(List<RecommendationTrend> trend) {
        AgoraCompanyData m = mock(AgoraCompanyData.class);
        when(m.recommendationsStrict(anyString())).thenReturn(trend);
        return m;
    }

    private static AgoraEarnings earnings(Optional<LocalDate> next) {
        AgoraEarnings m = mock(AgoraEarnings.class);
        when(m.nextEarningsDate(anyString())).thenReturn(next);
        return m;
    }

    private static final List<RecommendationTrend> TREND =
            List.of(new RecommendationTrend("2026-07-01", 1, 2, 1, 0, 0));

    @Test
    void enrichesAllContextFields() {
        LocalDate nextEarnings = LocalDate.now().plusDays(8);
        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.of(nextEarnings)));

        var out = svc.enrich(List.of(cluster("AAA")));

        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.ticker()).isEqualTo("AAA");
        assertThat(e.filers()).hasSize(3);
        assertThat(e.netInsiderDollar()).isEqualByComparingTo("1400000");
        assertThat(e.marketCap()).isEqualTo(850.0);
        assertThat(e.adv()).isEqualByComparingTo("10000000");
        assertThat(e.metricsAvailable()).isTrue();
        assertThat(e.analystCoverage()).isEqualTo(4);
        assertThat(e.coverageAvailable()).isTrue();
        assertThat(e.ytdReturn()).isEqualByComparingTo("-0.5");
        assertThat(e.ytdReturnAvailable()).isTrue();
        assertThat(e.nextEarningsDate()).isEqualTo(nextEarnings);
        assertThat(e.daysToEarnings()).isEqualTo(8);
        assertThat(e.earningsDateAvailable()).isTrue();
    }

    @Test
    void degradesMetricsOnlyWhenEquityMetricsUnavailable() {
        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(null, false), companyData(TREND),
                earnings(Optional.of(LocalDate.now().plusDays(30))));

        var e = svc.enrich(List.of(cluster("BBB"))).get(0);

        assertThat(e.marketCap()).isNull();
        // adv still computed from OHLC, so the metrics group stays available
        assertThat(e.adv()).isNotNull();
        assertThat(e.metricsAvailable()).isTrue();
        assertThat(e.coverageAvailable()).isTrue();
        assertThat(e.ytdReturnAvailable()).isTrue();
        assertThat(e.earningsDateAvailable()).isTrue();
    }

    @Test
    void degradesAdvAndYtdWhenOhlcUnavailable() {
        var svc = enrichmentService(marketDataThrowing(),
                equityMetrics(850.0, true), companyData(TREND),
                earnings(Optional.of(LocalDate.now().plusDays(30))));

        var e = svc.enrich(List.of(cluster("CCC"))).get(0);

        assertThat(e.adv()).isNull();
        assertThat(e.ytdReturn()).isNull();
        assertThat(e.ytdReturnAvailable()).isFalse();
        // marketCap alone keeps the metrics group available
        assertThat(e.marketCap()).isEqualTo(850.0);
        assertThat(e.metricsAvailable()).isTrue();
        assertThat(e.coverageAvailable()).isTrue();
        assertThat(e.earningsDateAvailable()).isTrue();
    }

    @Test
    void degradesCoverageWhenNoRecommendationTrend() {
        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(List.of()),
                earnings(Optional.of(LocalDate.now().plusDays(30))));

        var e = svc.enrich(List.of(cluster("DDD"))).get(0);

        assertThat(e.analystCoverage()).isNull();
        assertThat(e.coverageAvailable()).isFalse();
        assertThat(e.metricsAvailable()).isTrue();
        assertThat(e.ytdReturnAvailable()).isTrue();
        assertThat(e.earningsDateAvailable()).isTrue();
    }

    @Test
    void degradesEarningsWhenNoNextDate() {
        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()));

        var e = svc.enrich(List.of(cluster("EEE"))).get(0);

        assertThat(e.nextEarningsDate()).isNull();
        assertThat(e.daysToEarnings()).isNull();
        assertThat(e.earningsDateAvailable()).isFalse();
        assertThat(e.metricsAvailable()).isTrue();
    }

    @Test
    void degradesEverythingButNeverTheCluster() {
        AgoraCompanyData throwingCompanyData = mock(AgoraCompanyData.class);
        when(throwingCompanyData.recommendationsStrict(anyString())).thenThrow(new RuntimeException("boom"));
        AgoraEarnings throwingEarnings = mock(AgoraEarnings.class);
        when(throwingEarnings.nextEarningsDate(anyString())).thenThrow(new RuntimeException("boom"));

        var svc = enrichmentService(marketDataThrowing(),
                equityMetrics(null, false), throwingCompanyData, throwingEarnings);

        var out = svc.enrich(List.of(cluster("FFF")));

        assertThat(out).hasSize(1);
        var e = out.get(0);
        // core cluster fields survive untouched
        assertThat(e.ticker()).isEqualTo("FFF");
        assertThat(e.totalDollarValue()).isEqualByComparingTo("1800000");
        assertThat(e.concurrentInsiderSells()).isEqualTo(1);
        // all enrichment degraded
        assertThat(e.marketCap()).isNull();
        assertThat(e.adv()).isNull();
        assertThat(e.metricsAvailable()).isFalse();
        assertThat(e.analystCoverage()).isNull();
        assertThat(e.coverageAvailable()).isFalse();
        assertThat(e.ytdReturn()).isNull();
        assertThat(e.ytdReturnAvailable()).isFalse();
        assertThat(e.nextEarningsDate()).isNull();
        assertThat(e.daysToEarnings()).isNull();
        assertThat(e.earningsDateAvailable()).isFalse();
    }

    @Test
    void advIsNullWithFewerThanTwentyBars() {
        // only 10 bars this year: too thin for the 20-day ADV window, but enough for YTD
        var svc = enrichmentService(
                marketDataReturning(barsOn(LocalDate.now().minusDays(9), 10)),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()));

        var e = svc.enrich(List.of(cluster("GGG"))).get(0);

        assertThat(e.adv()).isNull();
        assertThat(e.ytdReturn()).isEqualByComparingTo("0");
        assertThat(e.ytdReturnAvailable()).isTrue();
        assertThat(e.metricsAvailable()).isTrue(); // marketCap present
    }

    @Test
    void ytdIsNullWithFewerThanTwoBarsInCurrentYear() {
        // 25 bars, all from the previous calendar year (the early-January path):
        // ADV is computable, YTD is not.
        var svc = enrichmentService(
                marketDataReturning(barsOn(LocalDate.of(LocalDate.now().getYear() - 1, 11, 1), 25)),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()));

        var e = svc.enrich(List.of(cluster("HHH"))).get(0);

        assertThat(e.adv()).isEqualByComparingTo("10000000");
        assertThat(e.ytdReturn()).isNull();
        assertThat(e.ytdReturnAvailable()).isFalse();
    }

    @Test
    void symbolSpecificFailureDoesNotDisableTheSource() {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics("AAA")).thenThrow(
                new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no such symbol"));
        when(m.metrics("BBB")).thenReturn(new EquityMetrics(1.0, 850.0, 100.0, 200.0, "Technology", true));

        var svc = enrichmentService(marketDataReturning(bars()),
                m, companyData(TREND), earnings(Optional.empty()));

        var out = svc.enrich(List.of(cluster("AAA"), cluster("BBB")));

        // NOT_FOUND is symbol-specific: AAA degrades, BBB is still queried and fully enriched
        verify(m, times(1)).metrics("AAA");
        verify(m, times(1)).metrics("BBB");
        var a = out.stream().filter(x -> x.ticker().equals("AAA")).findFirst().orElseThrow();
        var b = out.stream().filter(x -> x.ticker().equals("BBB")).findFirst().orElseThrow();
        assertThat(a.marketCap()).isNull();
        assertThat(b.marketCap()).isEqualTo(850.0);
        assertThat(b.coverageAvailable()).isTrue();
    }

    @Test
    void availabilityFailureSkipsThatSourceForRemainingClusters() {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics(anyString())).thenThrow(new AgoraUnavailableException("Agora unreachable"));
        AgoraCompanyData cd = companyData(TREND);
        AgoraEarnings earn = earnings(Optional.empty());

        var svc = enrichmentService(marketDataReturning(bars()), m, cd, earn);

        var out = svc.enrich(List.of(cluster("AAA"), cluster("BBB")));

        // the down source is queried exactly once, then skipped for the rest of the batch
        verify(m, times(1)).metrics(anyString());
        // the other sources keep serving every cluster
        verify(cd, times(2)).recommendationsStrict(anyString());
        verify(earn, times(2)).nextEarningsDate(anyString());
        assertThat(out).allSatisfy(e -> {
            assertThat(e.marketCap()).isNull();
            assertThat(e.adv()).isNotNull();           // OHLC still enriches
            assertThat(e.coverageAvailable()).isTrue();
        });
    }

    @Test
    void availabilityFailureSkipsRecommendationsForRemainingClusters() {
        // real short-circuit: recommendationsStrict propagates the outage (the swallowing
        // default recommendations() would return an empty list and never trip the guard)
        AgoraCompanyData cd = mock(AgoraCompanyData.class);
        when(cd.recommendationsStrict(anyString())).thenThrow(new AgoraUnavailableException("Agora unreachable"));

        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), cd, earnings(Optional.empty()));

        var out = svc.enrich(List.of(cluster("AAA"), cluster("BBB")));

        // the down source is queried exactly once, then skipped for the rest of the batch
        verify(cd, times(1)).recommendationsStrict(anyString());
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(e -> {
            assertThat(e.analystCoverage()).isNull();
            assertThat(e.coverageAvailable()).isFalse();
            assertThat(e.marketCap()).isEqualTo(850.0); // other sources keep serving
            assertThat(e.adv()).isNotNull();
        });
    }

    @Test
    void twoSourcesDownSkipEnrichmentForRemainingClusters() {
        EquityMetricsExtractor m = mock(EquityMetricsExtractor.class);
        when(m.metrics(anyString())).thenThrow(new AgoraUnavailableException("Agora unreachable"));
        AgoraCompanyData cd = companyData(TREND);
        AgoraEarnings earn = earnings(Optional.of(LocalDate.now().plusDays(30)));

        var svc = enrichmentService(marketDataThrowing(), m, cd, earn);

        var out = svc.enrich(List.of(cluster("AAA"), cluster("BBB"), cluster("CCC")));

        // metrics + ohlc are marked down during cluster 1 -> no source is queried again at all
        verify(m, times(1)).metrics(anyString());
        verify(cd, times(1)).recommendationsStrict(anyString());
        verify(earn, times(1)).nextEarningsDate(anyString());
        assertThat(out).hasSize(3);
        // clusters 2+3 are returned unenriched but with core fields intact
        var last = out.get(2);
        assertThat(last.ticker()).isNotBlank();
        assertThat(last.totalDollarValue()).isEqualByComparingTo("1800000");
        assertThat(last.metricsAvailable()).isFalse();
        assertThat(last.coverageAvailable()).isFalse();
        assertThat(last.ytdReturnAvailable()).isFalse();
        assertThat(last.earningsDateAvailable()).isFalse();
    }

    @Test
    void capsAtTwentyFiveClustersKeepingTheLargestByDollarValue() {
        var svc = enrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()));

        List<InsiderCluster> clusters = new ArrayList<>();
        for (int i = 0; i < 30; i++) clusters.add(cluster("SYM" + i, BigDecimal.valueOf(500_000 + i * 1000)));

        var out = svc.enrich(clusters);

        assertThat(out).hasSize(25);
        // sorted descending by totalDollarValue: the largest first, the 5 smallest dropped
        assertThat(out.get(0).totalDollarValue()).isEqualByComparingTo("529000");
        assertThat(out.get(24).totalDollarValue()).isEqualByComparingTo("505000");
        assertThat(out).noneMatch(e -> e.totalDollarValue().compareTo(BigDecimal.valueOf(505_000)) < 0);
    }

    @Test
    void classifiesFilersAndAggregatesOpportunisticShare() {
        LocalDate ref = LocalDate.now().minusDays(2);   // cluster windowEnd, i.e. the "current" buy
        int refMonthDay = 15;
        // Alice: buys in the reference month in each of the two prior years -> ROUTINE
        var alice = owner("Alice",
                buy(LocalDate.of(ref.getYear() - 1, ref.getMonthValue(), refMonthDay), 100, null, false),
                buy(LocalDate.of(ref.getYear() - 2, ref.getMonthValue(), refMonthDay), 100, null, false),
                buy(ref, 200, 1000L, false));                                   // current window buy
        // Carol: three purchases, none in a prior-year reference month -> OPPORTUNISTIC
        int offMonth = ((ref.getMonthValue() + 5) % 12) + 1;                    // clearly > 1 month away
        var carol = owner("Carol",
                buy(LocalDate.of(ref.getYear() - 1, offMonth, refMonthDay), 100, null, null),
                buy(LocalDate.of(ref.getYear() - 2, offMonth, refMonthDay), 100, null, null),
                buy(ref, 300, 3000L, true));                                    // current window buy, 10b5-1
        // Bob: a single purchase ever -> too thin -> UNKNOWN
        var bob = owner("Bob", buy(ref, 50, null, null));
        var history = new Form4OwnerHistory("CIK", null, null, List.of(alice, carol, bob), false);

        var svc = new InsiderEnrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()),
                filingsReturning(history), new RoutineClassifier());

        var e = svc.enrich(List.of(cluster("AAA"))).get(0);

        assertThat(e.classificationAvailable()).isTrue();
        assertThat(e.classifiedFilers()).isEqualTo(2);   // Alice + Carol
        assertThat(e.unknownFilers()).isEqualTo(1);      // Bob
        assertThat(e.opportunisticShare()).isEqualByComparingTo("0.5");  // 1 of 2 classifiable

        var byName = e.filers().stream()
                .collect(java.util.stream.Collectors.toMap(InsiderFiler::name, f -> f));
        assertThat(byName.get("Alice").classification()).isEqualTo(FilerClassification.ROUTINE);
        assertThat(byName.get("Carol").classification()).isEqualTo(FilerClassification.OPPORTUNISTIC);
        assertThat(byName.get("Bob").classification()).isEqualTo(FilerClassification.UNKNOWN);

        // current-purchase context threaded from the same owner history
        assertThat(byName.get("Alice").sharesOwnedFollowing()).isEqualByComparingTo("1000");
        assertThat(byName.get("Alice").purchaseAsPctOfHoldings()).isEqualByComparingTo("0.2"); // 200/1000
        assertThat(byName.get("Alice").planned10b5_1()).isFalse();
        assertThat(byName.get("Carol").purchaseAsPctOfHoldings()).isEqualByComparingTo("0.1"); // 300/3000
        assertThat(byName.get("Carol").planned10b5_1()).isTrue();                              // 10b5-1 plan
        assertThat(byName.get("Bob").planned10b5_1()).isNull();                                // unknown, not false
    }

    @Test
    void truncatedHistoryKeepsAbsenceOfPatternAsUnknownNotOpportunistic() {
        LocalDate ref = LocalDate.now().minusDays(2);
        int offMonth = ((ref.getMonthValue() + 5) % 12) + 1;
        // A full track record with no recurring pattern would be OPPORTUNISTIC — but truncated.
        var carol = owner("Carol",
                buy(LocalDate.of(ref.getYear() - 1, offMonth, 15), 100, null, null),
                buy(LocalDate.of(ref.getYear() - 2, offMonth, 15), 100, null, null),
                buy(ref, 300, null, null));
        var history = new Form4OwnerHistory("CIK", null, null, List.of(carol), true); // truncated!

        var svc = new InsiderEnrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()),
                filingsReturning(history), new RoutineClassifier());

        var e = svc.enrich(List.of(cluster("AAA"))).get(0);

        var carolOut = e.filers().stream().filter(f -> f.name().equals("Carol")).findFirst().orElseThrow();
        assertThat(carolOut.classification()).isEqualTo(FilerClassification.UNKNOWN);
    }

    @Test
    void ownerHistoryDownDegradesClassificationOnlyAndShortCircuitsRestOfBatch() {
        AgoraFilings filings = ownerHistoryThrowing();
        var svc = new InsiderEnrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()),
                filings, new RoutineClassifier());

        var out = svc.enrich(List.of(cluster("AAA"), cluster("BBB")));

        // an availability failure on cluster 1 marks the source down: cluster 2 is NOT queried
        verify(filings, times(1)).ownerHistoryStrict(anyString());
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(e -> {
            assertThat(e.classificationAvailable()).isFalse();
            assertThat(e.opportunisticShare()).isNull();
            assertThat(e.classifiedFilers()).isZero();
            assertThat(e.unknownFilers()).isEqualTo(3);
            assertThat(e.filers()).allMatch(f -> f.classification() == FilerClassification.UNKNOWN);
            // only the owner-history source degraded — the rest still enriched
            assertThat(e.marketCap()).isEqualTo(850.0);
            assertThat(e.coverageAvailable()).isTrue();
        });
    }

    @Test
    void unmatchedFilerIsUnknownNotOpportunistic() {
        // owner history for a DIFFERENT person only -> none of the cluster filers match
        LocalDate ref = LocalDate.now().minusDays(2);
        var stranger = owner("Zelda",
                buy(LocalDate.of(ref.getYear() - 1, ref.getMonthValue(), 15), 100, null, null),
                buy(LocalDate.of(ref.getYear() - 2, ref.getMonthValue(), 15), 100, null, null),
                buy(ref, 300, null, null));
        var history = new Form4OwnerHistory("CIK", null, null, List.of(stranger), false);

        var svc = new InsiderEnrichmentService(marketDataReturning(bars()),
                equityMetrics(850.0, true), companyData(TREND), earnings(Optional.empty()),
                filingsReturning(history), new RoutineClassifier());

        var e = svc.enrich(List.of(cluster("AAA"))).get(0);

        assertThat(e.classificationAvailable()).isTrue();   // the call succeeded
        assertThat(e.classifiedFilers()).isZero();          // but no filer matched an owner
        assertThat(e.unknownFilers()).isEqualTo(3);
        assertThat(e.opportunisticShare()).isNull();
    }
}
