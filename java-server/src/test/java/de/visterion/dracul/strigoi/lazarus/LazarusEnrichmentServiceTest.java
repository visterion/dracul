package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.echo.RevisionsProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LazarusEnrichmentServiceTest {

    private static final FundamentalScore GOOD_SCORE =
            new FundamentalScore(7, 8, BigDecimal.valueOf(0.05), true, true, true);

    private AgoraFilings filings;
    private AgoraMarketData marketData;
    private AltmanZCalculator altmanZ;
    private AgoraCompanyData companyData;
    private LazarusEnrichmentService service;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        marketData = mock(AgoraMarketData.class); // unstubbed -> empty bar list (no timing)
        altmanZ = mock(AltmanZCalculator.class);
        companyData = mock(AgoraCompanyData.class); // unstubbed -> empty trend (no revisions)
        when(altmanZ.zScore(anyString(), any(), any())).thenReturn(AltmanZCalculator.AltmanZ.unavailable());
        service = new LazarusEnrichmentService(filings, marketData, altmanZ, companyData,
                new RevisionsProxy());
    }

    private static LazarusCandidate candidate(String symbol) {
        return candidate(symbol, 0.05);
    }

    private static LazarusCandidate candidate(String symbol, double pctAboveLow) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, pctAboveLow,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, 900.0);
    }

    /** {@code count} consecutive daily bars ending at {@code end}, all closing at {@code close}. */
    private static List<OhlcBar> flatBars(int count, BigDecimal close, LocalDate end) {
        List<OhlcBar> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            LocalDate d = end.minusDays(i);
            out.add(new OhlcBar(d, close, close, close, close, 1_000_000L));
        }
        return out;
    }

    /** 260 consecutive daily bars ending today with hand-placed segments:
     *  <pre>
     *  index   0..196 close 20   (old plateau)
     *  index      197 close 16   (the ~63-bars-ago momentum reference: 260 - 63)
     *  index 198..216 close 15   (decline)
     *  index      217 close 10   (unique 52w closing low, 42 days = 6 full weeks before the end)
     *  index 218..259 close 12   (stabilization)
     *  </pre>
     *  Expected: priceVs50dMa = 12 / ((7*15 + 10 + 42*12) / 50) - 1 = 12/12.38 - 1 = -0.0307;
     *  weeksSinceNewLow = 42 / 7 = 6; momentum3m = 12/16 - 1 = -0.2500. */
    private static List<OhlcBar> stabilizingBars() {
        LocalDate end = LocalDate.now();
        List<OhlcBar> out = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            BigDecimal close;
            if (i <= 196) close = BigDecimal.valueOf(20);
            else if (i == 197) close = BigDecimal.valueOf(16);
            else if (i <= 216) close = BigDecimal.valueOf(15);
            else if (i == 217) close = BigDecimal.TEN;
            else close = BigDecimal.valueOf(12);
            LocalDate d = end.minusDays(259 - i);
            out.add(new OhlcBar(d, close, close, close, close, 1_000_000L));
        }
        return out;
    }

    @Test
    void mapsFundamentalScoreOntoCandidate() {
        when(filings.fundamentalScoreStrict("ACME")).thenReturn(GOOD_SCORE);

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("ACME")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.symbol()).isEqualTo("ACME");
        assertThat(e.fScore()).isEqualTo(7);
        assertThat(e.fScoreCriteriaAvailable()).isEqualTo(8);
        assertThat(e.accrualRatio()).isEqualByComparingTo(BigDecimal.valueOf(0.05));
        assertThat(e.cfoExceedsNetIncome()).isTrue();
        assertThat(e.cfoExceedsNetIncomeAvailable()).isTrue();
    }

    @Test
    void dropsCandidateWhenAccrualsGateFails() {
        when(filings.fundamentalScoreStrict("BADCO")).thenReturn(new FundamentalScore(
                3, 8, BigDecimal.valueOf(0.15), false, true, true));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("BADCO")));

        assertThat(out).isEmpty();
        // a hard-dropped candidate costs no further remote calls: no OHLC, no recommendations
        verifyNoInteractions(marketData, companyData);
        verify(altmanZ, never()).zScore(anyString(), any(), any()); // stubbed in setUp, so verify never()
    }

    @Test
    void keepsCandidateWhenAccrualsSignalUnavailable() {
        when(filings.fundamentalScoreStrict("NOACCR")).thenReturn(new FundamentalScore(
                5, 6, null, false, false, true));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NOACCR")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).symbol()).isEqualTo("NOACCR");
        assertThat(out.get(0).cfoExceedsNetIncomeAvailable()).isFalse();
        assertThat(out.get(0).cfoExceedsNetIncome()).isFalse(); // unavailable serializes as false
    }

    @Test
    void keepsCandidateWithZeroScoreWhenFundamentalScoreUnavailable() {
        when(filings.fundamentalScoreStrict("NODATA")).thenReturn(FundamentalScore.unavailable());

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NODATA")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.fScore()).isEqualTo(0);
        assertThat(e.fScoreCriteriaAvailable()).isEqualTo(0);
        assertThat(e.cfoExceedsNetIncomeAvailable()).isFalse();
    }

    @Test
    void computesAllThreeTimingSignalsFromFullOhlcSeries() {
        when(filings.fundamentalScoreStrict("STAB")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("STAB"), anyInt())).thenReturn(stabilizingBars());

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("STAB"))).get(0);

        assertThat(e.priceVs50dMa()).isEqualByComparingTo("-0.0307");
        assertThat(e.weeksSinceNewLow()).isEqualTo(6);
        assertThat(e.momentum3m()).isEqualByComparingTo("-0.2500");
        assertThat(e.timingAvailable()).isTrue();
        // fundamental enrichment rides alongside untouched
        assertThat(e.fScore()).isEqualTo(7);
    }

    @Test
    void freshLowYieldsZeroWeeksSinceNewLow() {
        when(filings.fundamentalScoreStrict("FRESH")).thenReturn(GOOD_SCORE);
        // 260 flat bars, then the last bar sets a new low today -> 0 full weeks
        List<OhlcBar> bars = new ArrayList<>(flatBars(259, BigDecimal.valueOf(20), LocalDate.now().minusDays(1)));
        bars.add(new OhlcBar(LocalDate.now(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1_000_000L));
        when(marketData.dailyOhlcHistory(eq("FRESH"), anyInt())).thenReturn(bars);

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("FRESH"))).get(0);

        assertThat(e.weeksSinceNewLow()).isZero();
        assertThat(e.timingAvailable()).isTrue();
    }

    @Test
    void allTimingSignalsNullBelowFiftyBars() {
        when(filings.fundamentalScoreStrict("THIN")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("THIN"), anyInt()))
                .thenReturn(flatBars(40, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("THIN"))).get(0);

        assertThat(e.priceVs50dMa()).isNull();
        assertThat(e.weeksSinceNewLow()).isNull();
        assertThat(e.momentum3m()).isNull();
        assertThat(e.timingAvailable()).isFalse();
    }

    @Test
    void onlyMaSignalBetweenFiftyAndSixtyThreeBars() {
        when(filings.fundamentalScoreStrict("MAONLY")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("MAONLY"), anyInt()))
                .thenReturn(flatBars(60, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("MAONLY"))).get(0);

        assertThat(e.priceVs50dMa()).isEqualByComparingTo("0.0000"); // flat series: on the MA
        assertThat(e.momentum3m()).isNull();
        assertThat(e.weeksSinceNewLow()).isNull();
        assertThat(e.timingAvailable()).isTrue(); // mixed case: one signal is enough
    }

    @Test
    void weeksSinceNewLowNullBelowFullLowWindow() {
        when(filings.fundamentalScoreStrict("MIDLEN")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("MIDLEN"), anyInt()))
                .thenReturn(flatBars(100, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("MIDLEN"))).get(0);

        assertThat(e.priceVs50dMa()).isEqualByComparingTo("0.0000");
        assertThat(e.momentum3m()).isEqualByComparingTo("0.0000");
        assertThat(e.weeksSinceNewLow()).isNull(); // <252 bars: no full 52-week window
        assertThat(e.timingAvailable()).isTrue();
    }

    @Test
    void keepsCandidateWithoutTimingWhenOhlcUnavailable() {
        when(filings.fundamentalScoreStrict("NOHLC")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("NOHLC"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "outage"));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NOHLC")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.priceVs50dMa()).isNull();
        assertThat(e.weeksSinceNewLow()).isNull();
        assertThat(e.momentum3m()).isNull();
        assertThat(e.timingAvailable()).isFalse();
        assertThat(e.fScore()).isEqualTo(7); // fundamental enrichment unaffected
    }

    @Test
    void availabilityFailureSkipsOhlcForRemainingCandidates() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(anyString(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "outage"));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("AAA"), candidate("BBB")));

        // the down source is queried exactly once, then skipped for the rest of the batch
        verify(marketData, times(1)).dailyOhlcHistory(anyString(), anyInt());
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(e -> assertThat(e.timingAvailable()).isFalse());

        // the down-flag lives per enrich() call: the next batch probes the source again
        service.enrich(List.of(candidate("CCC"), candidate("DDD")));
        verify(marketData, times(2)).dailyOhlcHistory(anyString(), anyInt());
    }

    @Test
    void maSignalPresentAtExactlyFiftyBars() {
        when(filings.fundamentalScoreStrict("EDGE50")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("EDGE50"), anyInt()))
                .thenReturn(flatBars(50, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("EDGE50"))).get(0);

        assertThat(e.priceVs50dMa()).isEqualByComparingTo("0.0000"); // inclusive boundary
        assertThat(e.momentum3m()).isNull();
        assertThat(e.weeksSinceNewLow()).isNull();
    }

    @Test
    void momentumSignalPresentAtExactlySixtyThreeBars() {
        when(filings.fundamentalScoreStrict("EDGE63")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("EDGE63"), anyInt()))
                .thenReturn(flatBars(63, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("EDGE63"))).get(0);

        assertThat(e.momentum3m()).isEqualByComparingTo("0.0000"); // inclusive boundary
        assertThat(e.priceVs50dMa()).isEqualByComparingTo("0.0000");
        assertThat(e.weeksSinceNewLow()).isNull();
    }

    @Test
    void weeksSinceNewLowPresentAtExactlyTwoFiftyTwoBars() {
        when(filings.fundamentalScoreStrict("EDGE252")).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("EDGE252"), anyInt()))
                .thenReturn(flatBars(252, BigDecimal.TEN, LocalDate.now()));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("EDGE252"))).get(0);

        assertThat(e.weeksSinceNewLow()).isZero(); // inclusive boundary; flat ties -> latest bar
        assertThat(e.priceVs50dMa()).isEqualByComparingTo("0.0000");
        assertThat(e.momentum3m()).isEqualByComparingTo("0.0000");
        assertThat(e.timingAvailable()).isTrue();
    }

    @Test
    void symbolSpecificNotFoundDoesNotDisableTheOhlcSource() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(marketData.dailyOhlcHistory(eq("GONE"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no such symbol"));
        when(marketData.dailyOhlcHistory(eq("HERE"), anyInt()))
                .thenReturn(flatBars(260, BigDecimal.TEN, LocalDate.now()));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("GONE"), candidate("HERE")));

        verify(marketData, times(1)).dailyOhlcHistory(eq("GONE"), anyInt());
        verify(marketData, times(1)).dailyOhlcHistory(eq("HERE"), anyInt());
        EnrichedLazarusCandidate gone = out.get(0);
        EnrichedLazarusCandidate here = out.get(1);
        assertThat(gone.timingAvailable()).isFalse();
        assertThat(here.timingAvailable()).isTrue();
        assertThat(here.weeksSinceNewLow()).isZero(); // flat series: the low is the last tie
    }

    // --- Altman-Z distress screen ---

    @Test
    void mapsZScoreOntoCandidateAndPassesMarketCapThrough() {
        when(filings.fundamentalScoreStrict("ACME")).thenReturn(GOOD_SCORE);
        when(altmanZ.zScore(eq("ACME"), eq(900.0), any()))
                .thenReturn(new AltmanZCalculator.AltmanZ(new BigDecimal("3.40"), true));

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("ACME"))).get(0);

        assertThat(e.zScore()).isEqualByComparingTo("3.40");
        assertThat(e.zScoreAvailable()).isTrue();
        verify(altmanZ).zScore(eq("ACME"), eq(900.0), any()); // the candidate's Finnhub marketCap (USD millions)
    }

    @Test
    void zNotAttemptedWhenFundamentalScoreUnavailable() {
        when(filings.fundamentalScoreStrict("NODATA")).thenReturn(FundamentalScore.unavailable());

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("NODATA"))).get(0);

        // an unavailable F-score means the symbol may not resolve in EDGAR at all — attempting
        // the concept fetches would burn dead remote calls and could false-trip the down guard
        verify(altmanZ, never()).zScore(anyString(), any(), any());
        assertThat(e.zScore()).isNull();
        assertThat(e.zScoreAvailable()).isFalse();
    }

    @Test
    void unavailableZKeepsCandidateWithZUnknown() {
        when(filings.fundamentalScoreStrict("NOZ")).thenReturn(GOOD_SCORE);
        // setUp default: altmanZ -> unavailable

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("NOZ"))).get(0);

        assertThat(e.zScore()).isNull();
        assertThat(e.zScoreAvailable()).isFalse();
        assertThat(e.fScore()).isEqualTo(7); // rest of the enrichment unaffected
    }

    @Test
    void agoraOutageDuringZDisablesZForRemainingCandidates() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(altmanZ.zScore(anyString(), any(), any())).thenThrow(new AgoraUnavailableException("down"));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("AAA"), candidate("BBB")));

        // the down source is probed exactly once, then skipped for the rest of the batch
        verify(altmanZ, times(1)).zScore(anyString(), any(), any());
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(e -> {
            assertThat(e.zScore()).isNull();
            assertThat(e.zScoreAvailable()).isFalse();
            assertThat(e.fScore()).isEqualTo(7); // fundamental enrichment unaffected
        });

        // the down-flag lives per enrich() call: the next batch probes the source again
        service.enrich(List.of(candidate("CCC")));
        verify(altmanZ, times(2)).zScore(anyString(), any(), any());
    }

    @Test
    void nonAvailabilityZFailureStaysPerCandidate() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(altmanZ.zScore(eq("BOOM"), any(), any())).thenThrow(new IllegalStateException("weird payload"));
        when(altmanZ.zScore(eq("FINE"), any(), any()))
                .thenReturn(new AltmanZCalculator.AltmanZ(new BigDecimal("2.10"), true));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("BOOM"), candidate("FINE")));

        assertThat(out.get(0).zScoreAvailable()).isFalse(); // fail-soft, candidate kept
        assertThat(out.get(1).zScore()).isEqualByComparingTo("2.10"); // source not disabled
    }

    // --- F-score source-down guard ---

    @Test
    void agoraOutageDuringFScoreDisablesTheFetchForRemainingCandidates() {
        when(filings.fundamentalScoreStrict(anyString())).thenThrow(new AgoraUnavailableException("down"));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("AAA"), candidate("BBB")));

        // the down source is probed exactly once, then skipped for the rest of the batch
        verify(filings, times(1)).fundamentalScoreStrict(anyString());
        assertThat(out).hasSize(2); // fail-soft: candidates ride through score-less, as with unavailable
        assertThat(out).allSatisfy(e -> {
            assertThat(e.fScore()).isZero();
            assertThat(e.fScoreCriteriaAvailable()).isZero();
            assertThat(e.zScoreAvailable()).isFalse();
        });
        verify(altmanZ, never()).zScore(anyString(), any(), any()); // no Z without a resolved F-score

        // the down-flag lives per enrich() call: the next batch probes the source again
        service.enrich(List.of(candidate("CCC")));
        verify(filings, times(2)).fundamentalScoreStrict(anyString());
    }

    @Test
    void nonAvailabilityFScoreFailureStaysPerCandidate() {
        when(filings.fundamentalScoreStrict("BOOM")).thenThrow(new IllegalStateException("weird payload"));
        when(filings.fundamentalScoreStrict("FINE")).thenReturn(GOOD_SCORE);

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("BOOM"), candidate("FINE")));

        verify(filings, times(2)).fundamentalScoreStrict(anyString()); // source not disabled
        assertThat(out.get(0).fScore()).isZero();     // fail-soft, candidate kept
        assertThat(out.get(1).fScore()).isEqualTo(7);
    }

    // --- forward revisions + analyst coverage ---

    /** Newest-first: latest net = 2+5-1-0 = 6, previous net = 1+3-2-1 = 1 -> proxy 5 ("up");
     *  coverage = latest-period total = 2+5+4+1+0 = 12. */
    private static final List<RecommendationTrend> UP_TREND = List.of(
            new RecommendationTrend("2026-07-01", 2, 5, 4, 1, 0),
            new RecommendationTrend("2026-06-01", 1, 3, 6, 2, 1));

    /** Newest-first: latest net = 0+2-3-1 = -2, previous net = 2+4-1-0 = 5 -> proxy -7 ("down");
     *  coverage = latest-period total = 0+2+6+3+1 = 12. */
    private static final List<RecommendationTrend> DOWN_TREND = List.of(
            new RecommendationTrend("2026-07-01", 0, 2, 6, 3, 1),
            new RecommendationTrend("2026-06-01", 2, 4, 5, 1, 0));

    @Test
    void mapsRevisionsProxyDirectionAndCoverageFromRecommendationTrend() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(companyData.recommendationsStrict("UP")).thenReturn(UP_TREND);
        when(companyData.recommendationsStrict("DOWN")).thenReturn(DOWN_TREND);

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("UP", 0.01), candidate("DOWN", 0.02)));

        EnrichedLazarusCandidate up = out.get(0);
        assertThat(up.netEstimateRevisionsProxy()).isEqualTo(5);
        assertThat(up.netEstimateRevisionsDirection()).isEqualTo("up");
        assertThat(up.analystCoverage()).isEqualTo(12);
        assertThat(up.revisionsAvailable()).isTrue();
        assertThat(up.fScore()).isEqualTo(7); // fundamental enrichment rides alongside untouched

        EnrichedLazarusCandidate down = out.get(1);
        assertThat(down.netEstimateRevisionsProxy()).isEqualTo(-7);
        assertThat(down.netEstimateRevisionsDirection()).isEqualTo("down");
        assertThat(down.analystCoverage()).isEqualTo(12);
        assertThat(down.revisionsAvailable()).isTrue();
    }

    @Test
    void keepsCandidateWithRevisionsUnknownWhenTrendEmpty() {
        when(filings.fundamentalScoreStrict("NOTREND")).thenReturn(GOOD_SCORE);
        // setUp default: companyData unstubbed -> empty trend list

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NOTREND")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.netEstimateRevisionsProxy()).isNull();
        assertThat(e.netEstimateRevisionsDirection()).isNull();
        assertThat(e.analystCoverage()).isNull(); // one shared flag: all three null together
        assertThat(e.revisionsAvailable()).isFalse();
        assertThat(e.fScore()).isEqualTo(7); // rest of the enrichment unaffected
    }

    @Test
    void availabilityFailureSkipsRecommendationsForRemainingCandidates() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(companyData.recommendationsStrict(anyString())).thenThrow(new AgoraUnavailableException("down"));

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("AAA"), candidate("BBB")));

        // the down source is probed exactly once, then skipped for the rest of the batch
        verify(companyData, times(1)).recommendationsStrict(anyString());
        assertThat(out).hasSize(2); // fail-soft: candidates ride through revisions-less
        assertThat(out).allSatisfy(e -> {
            assertThat(e.revisionsAvailable()).isFalse();
            assertThat(e.netEstimateRevisionsProxy()).isNull();
            assertThat(e.analystCoverage()).isNull();
            assertThat(e.fScore()).isEqualTo(7); // fundamental enrichment unaffected
        });

        // the down-flag lives per enrich() call: the next batch probes the source again
        service.enrich(List.of(candidate("CCC"), candidate("DDD")));
        verify(companyData, times(2)).recommendationsStrict(anyString());
    }

    @Test
    void symbolSpecificRecommendationsFailureDoesNotDisableTheSource() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(companyData.recommendationsStrict("BOOM")).thenThrow(new IllegalStateException("weird payload"));
        when(companyData.recommendationsStrict("FINE")).thenReturn(UP_TREND);

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("BOOM", 0.01), candidate("FINE", 0.02)));

        verify(companyData, times(2)).recommendationsStrict(anyString()); // source not disabled
        assertThat(out.get(0).revisionsAvailable()).isFalse(); // fail-soft, candidate kept
        assertThat(out.get(1).revisionsAvailable()).isTrue();
        assertThat(out.get(1).netEstimateRevisionsProxy()).isEqualTo(5);
    }

    // --- batch cap ---

    @Test
    void capsAtTwentyFiveCandidatesKeepingTheClosestToTheLow() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(LazarusEnrichmentService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<LazarusCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < 30; i++) candidates.add(candidate("SYM" + i, 0.001 * i));

            List<EnrichedLazarusCandidate> out = service.enrich(candidates);

            assertThat(out).hasSize(25);
            // sorted ASCENDING by pctAboveLow: SYM0..SYM24 (closest to the low) survive,
            // the 5 with the LARGEST pctAboveLow (SYM25..SYM29) are dropped
            assertThat(out.get(0).symbol()).isEqualTo("SYM0");
            assertThat(out.get(24).symbol()).isEqualTo("SYM24");
            assertThat(out).noneMatch(e -> e.pctAboveLow() > 0.024 + 1e-9);
            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(ev.getFormattedMessage()).contains("30 candidates exceed the cap of 25");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void batchAtTheCapIsNotTruncatedAndNotLogged() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(LazarusEnrichmentService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<LazarusCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < 25; i++) candidates.add(candidate("SYM" + i, 0.001 * i));

            assertThat(service.enrich(candidates)).hasSize(25); // inclusive boundary
            assertThat(appender.list).noneMatch(ev -> ev.getFormattedMessage().contains("cap"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
