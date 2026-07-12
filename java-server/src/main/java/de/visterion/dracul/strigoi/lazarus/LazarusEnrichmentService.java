package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.EnrichmentSourceGuard;
import de.visterion.dracul.strigoi.echo.AnalystCoverage;
import de.visterion.dracul.strigoi.echo.EarningsRevisions;
import de.visterion.dracul.strigoi.echo.RevisionsProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Enriches screened quality-at-52w-low candidates with Agora's Piotroski F-Score. The batch
 *  is first sorted by {@code pctAboveLow} ASCENDING and bounded to {@link #MAX_CANDIDATES}:
 *  every enrichment signal costs remote Agora calls per candidate, and for this hunter the
 *  names closest to their 52-week low are the interesting ones — a pctAboveLow ranking is the
 *  only meaningful priority available BEFORE any enrichment data has been fetched (sorting by
 *  F-score would require fetching it for the whole, uncapped batch first; the insider cap
 *  sorts by dollar value for the same pre-fetch reason). A candidate
 *  is hard-dropped when Agora reports it burns more cash than it books as net income (accruals
 *  red flag) — otherwise the enrichment is purely additive, degrading to a zero/unavailable
 *  score rather than dropping the candidate on any lookup failure.
 *
 *  <p>Surviving candidates additionally get deterministic timing/stabilization signals
 *  (price vs 50-day MA, weeks since the 52-week closing low, ~3-month momentum) computed from
 *  ONE Agora daily-OHLC query (~260 trading days) per candidate, so the LLM can tell a falling
 *  knife from base building instead of guessing from {@code pctAboveLow} alone. Fail-soft per
 *  candidate; an <em>availability</em> failure ({@link AgoraUnavailableException} or
 *  {@link MarketDataException} of kind UNAVAILABLE — as opposed to a symbol-specific
 *  NOT_FOUND) marks the OHLC source down for the remaining candidates of the batch, mirroring
 *  the insider-enrichment latency guard (a dead Agora call burns ~16s of the webhook's 30s
 *  budget).
 *
 *  <p>Surviving candidates finally get a classic Altman Z-Score ({@link AltmanZCalculator})
 *  as a distress screen, fail-soft to {@code zScoreAvailable=false}. Z is only attempted
 *  when the candidate's fundamental score was itself available: both come from the same
 *  EDGAR companyfacts on the Agora side, so a successful F-score lookup proves the symbol
 *  resolves — which in turn means an {@link AgoraUnavailableException} during the Z concept
 *  fetches is a genuine availability failure and (mirroring the OHLC guard) marks the
 *  concept source down for the remaining candidates of the batch.
 *
 *  <p>Finally each surviving candidate gets the echo SP3 forward-revisions read from ONE
 *  additional recommendation-trend call ({@link AgoraCompanyData#recommendationsStrict} —
 *  the strict variant, because the default {@code recommendations()} swallows outages into
 *  an empty list and would render the guard dead code): {@link RevisionsProxy} derives the
 *  net-revisions proxy/direction, {@link AnalystCoverage} the analyst count — both from the
 *  same response, so coverage costs no extra call. Fail-soft per candidate; an availability
 *  failure marks the recommendations source down for the remaining candidates of the batch,
 *  exactly like the OHLC guard. */
@Component
public class LazarusEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(LazarusEnrichmentService.class);

    /** Calendar days requested from get_ohlc — ~270 trading days of bars, comfortable headroom
     *  over the 252-bar low window even when the series has data gaps. */
    private static final int OHLC_LOOKBACK_DAYS = 400;
    /** Bars needed for the 50-day moving average. */
    private static final int MA_BARS = 50;
    /** Bars back for the ~3-month momentum reference close. */
    private static final int MOMENTUM_BARS = 63;
    /** Bars (~52 trading weeks) the weeks-since-new-low window must cover to be meaningful. */
    private static final int LOW_WINDOW_BARS = 252;
    /** Upper bound of candidates enriched per batch (sorted by pctAboveLow ascending first). */
    private static final int MAX_CANDIDATES = 25;

    private final AgoraFilings filings;
    private final AgoraMarketData marketData;
    private final AltmanZCalculator altmanZ;
    private final AgoraCompanyData companyData;
    private final RevisionsProxy revisionsProxy;

    public LazarusEnrichmentService(AgoraFilings filings, AgoraMarketData marketData,
            AltmanZCalculator altmanZ, AgoraCompanyData companyData, RevisionsProxy revisionsProxy) {
        this.filings = filings;
        this.marketData = marketData;
        this.altmanZ = altmanZ;
        this.companyData = companyData;
        this.revisionsProxy = revisionsProxy;
    }

    /** Timing/stabilization signals of one candidate; any field may be null (unknown). */
    private record TimingSignals(BigDecimal priceVs50dMa, Integer weeksSinceNewLow, BigDecimal momentum3m) {
        static final TimingSignals EMPTY = new TimingSignals(null, null, null);

        boolean available() {
            return priceVs50dMa != null || weeksSinceNewLow != null || momentum3m != null;
        }
    }

    public List<EnrichedLazarusCandidate> enrich(List<LazarusCandidate> candidates) {
        List<LazarusCandidate> bounded = candidates.stream()
                .sorted(Comparator.comparingDouble(LazarusCandidate::pctAboveLow))
                .limit(MAX_CANDIDATES)
                .toList();
        if (candidates.size() > MAX_CANDIDATES) {
            log.info("lazarus enrichment: {} candidates exceed the cap of {}, dropping the {} farthest above their 52w low",
                    candidates.size(), MAX_CANDIDATES, candidates.size() - MAX_CANDIDATES);
        }
        var out = new ArrayList<EnrichedLazarusCandidate>();
        boolean scoreDown = false;
        boolean ohlcDown = false;
        boolean conceptsDown = false;
        boolean revisionsDown = false;
        for (LazarusCandidate c : bounded) {
            FundamentalScore s = FundamentalScore.unavailable();
            if (!scoreDown) {
                try {
                    s = filings.fundamentalScoreStrict(c.symbol());
                } catch (AgoraUnavailableException e) {
                    scoreDown = true;
                    log.warn("lazarus enrichment: fundamental-score source down ({}), skipping it "
                            + "for the remaining candidates", e.getMessage());
                } catch (RuntimeException e) {
                    log.debug("lazarus enrichment: fundamental score unavailable for {}: {}", c.symbol(), e.getMessage());
                }
            }
            if (s.cfoExceedsNetIncomeAvailable() && !s.cfoExceedsNetIncome()) {
                log.debug("lazarus enrichment dropped {}: cfo does not exceed net income (accruals)", c.symbol());
                continue; // accruals hard-drop
            }
            TimingSignals t = TimingSignals.EMPTY;
            if (!ohlcDown) {
                try {
                    t = timingSignalsFrom(marketData.dailyOhlcHistory(c.symbol(), OHLC_LOOKBACK_DAYS));
                } catch (RuntimeException e) {
                    ohlcDown = EnrichmentSourceGuard.isSourceDown(e, "lazarus", "candidates", "ohlc");
                    log.debug("lazarus enrichment: ohlc history unavailable for {}: {}", c.symbol(), e.getMessage());
                }
            }
            AltmanZCalculator.AltmanZ z = AltmanZCalculator.AltmanZ.unavailable();
            // Only attempt Z when the F-score resolved: same EDGAR companyfacts behind both,
            // so a failed F-score would make every concept call a dead ~16s remote round trip
            // (and, for an EDGAR-unknown symbol, would wrongly trip the source-down guard).
            if (!conceptsDown && s.available()) {
                try {
                    z = altmanZ.zScore(c.symbol(), c.marketCap());
                } catch (AgoraUnavailableException e) {
                    conceptsDown = true;
                    log.warn("lazarus enrichment: concept source down ({}), skipping altman-z "
                            + "for the remaining candidates", e.getMessage());
                } catch (RuntimeException e) {
                    log.debug("lazarus enrichment: altman-z unavailable for {}: {}", c.symbol(), e.getMessage());
                }
            }
            EarningsRevisions rev = EarningsRevisions.unavailable();
            AnalystCoverage cov = AnalystCoverage.of(List.of());
            if (!revisionsDown) {
                try {
                    List<RecommendationTrend> trend = companyData.recommendationsStrict(c.symbol());
                    rev = revisionsProxy.revisions(trend);
                    cov = AnalystCoverage.of(trend);
                } catch (RuntimeException e) {
                    revisionsDown = EnrichmentSourceGuard.isSourceDown(e, "lazarus", "candidates", "recommendations");
                    log.debug("lazarus enrichment: recommendations unavailable for {}: {}",
                            c.symbol(), e.getMessage());
                }
            }
            out.add(new EnrichedLazarusCandidate(
                    c.symbol(), c.companyName(), c.currentPrice(), c.week52Low(), c.week52High(),
                    c.pctAboveLow(), c.roaTtm(), c.currentRatio(), c.debtToEquity(), c.grossMargin(),
                    c.netMargin(), c.revenueGrowthYoy(), c.epsGrowthYoy(), c.priceToBook(), c.peTtm(),
                    c.fcfPerShare(), s.score(), s.criteriaAvailable(), s.accrualRatio(),
                    s.cfoExceedsNetIncome(), s.cfoExceedsNetIncomeAvailable(),
                    t.priceVs50dMa(), t.weeksSinceNewLow(), t.momentum3m(), t.available(),
                    z.zScore(), z.available(),
                    rev.netProxy(), rev.direction(), cov.coverage(), rev.available()));
        }
        return out;
    }

    /** All three timing signals from one oldest-first daily-OHLC series; bars without a
     *  positive close are dropped up front. Each signal degrades to null independently when
     *  the (filtered) series is too short for its window. */
    private static TimingSignals timingSignalsFrom(List<OhlcBar> bars) {
        List<OhlcBar> usable = bars.stream()
                .filter(b -> b.close() != null && b.close().signum() > 0)
                .toList();
        if (usable.isEmpty()) return TimingSignals.EMPTY;
        return new TimingSignals(priceVs50dMaFrom(usable), weeksSinceNewLowFrom(usable), momentum3mFrom(usable));
    }

    /** last close / mean of the last {@link #MA_BARS} closes − 1, decimal fraction; null with
     *  fewer than {@link #MA_BARS} bars. */
    private static BigDecimal priceVs50dMaFrom(List<OhlcBar> bars) {
        if (bars.size() < MA_BARS) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (OhlcBar b : bars.subList(bars.size() - MA_BARS, bars.size())) {
            sum = sum.add(b.close());
        }
        // extra headroom on the intermediate mean so the final ratio rounds once, at scale 4
        BigDecimal ma = sum.divide(BigDecimal.valueOf(MA_BARS), 8, RoundingMode.HALF_UP);
        if (ma.signum() <= 0) return null;
        BigDecimal last = bars.get(bars.size() - 1).close();
        return last.divide(ma, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    /** Full weeks between the trading day that (most recently) set the lowest close of the last
     *  {@link #LOW_WINDOW_BARS} bars and the last bar's date; 0 = the low is less than a week
     *  old. Null when the series does not cover the full ~52-week window. */
    private static Integer weeksSinceNewLowFrom(List<OhlcBar> bars) {
        if (bars.size() < LOW_WINDOW_BARS) return null;
        List<OhlcBar> window = bars.subList(bars.size() - LOW_WINDOW_BARS, bars.size());
        OhlcBar lowBar = window.get(0);
        for (OhlcBar b : window) {
            if (b.close().compareTo(lowBar.close()) <= 0) lowBar = b; // ties -> most recent low
        }
        long days = ChronoUnit.DAYS.between(lowBar.date(), bars.get(bars.size() - 1).date());
        return (int) (days / 7);
    }

    /** last close / close ~{@link #MOMENTUM_BARS} bars earlier − 1, decimal fraction; the
     *  reference is the oldest close of a {@link #MOMENTUM_BARS}-bar window, i.e. strictly 62
     *  bar intervals before the last close (economically equivalent; the golden fixtures pin
     *  this index). Null with fewer than {@link #MOMENTUM_BARS} bars. */
    private static BigDecimal momentum3mFrom(List<OhlcBar> bars) {
        if (bars.size() < MOMENTUM_BARS) return null;
        BigDecimal reference = bars.get(bars.size() - MOMENTUM_BARS).close();
        if (reference.signum() <= 0) return null;
        BigDecimal last = bars.get(bars.size() - 1).close();
        return last.divide(reference, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }
}
