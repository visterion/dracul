package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.echo.ConfounderScreen;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import de.visterion.dracul.strigoi.echo.MarketSignalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ANNOUNCED-stage enrichment for a tracked index-reconstitution event: the forced-demand /
 * liquidity read that lets the LLM judge the magnitude of the announcement&rarr;effective buying
 * window against real numbers rather than guessing. Persisted as {@code announced_snapshot}. Pure
 * (given its injected facades) and fail-soft per field, mirroring the spin snapshotters.
 *
 * <ul>
 *   <li><b>adv</b> / <b>avgVolume20d</b> — average daily dollar volume (close &times; volume) and
 *       average daily share volume over the last {@value #ADV_LOOKBACK} trading bars, carried over
 *       verbatim from the deleted {@code IndexEnrichmentService} (same {@link AgoraMarketData} OHLC
 *       source and arithmetic). Null when fewer than {@value #ADV_LOOKBACK} bars resolve.</li>
 *   <li><b>marketCap</b> — Finnhub {@code marketCapitalization} (USD millions) via the swallowing
 *       {@link EquityMetricsExtractor}. Independent of the price source.</li>
 *   <li><b>idiosyncraticVol</b> — the sample standard deviation of the last ~{@code idioVolLookback}
 *       daily residual returns (stock return minus beta&times;market return) computed with the shared
 *       {@link MarketSignalService#residualReturns} helper against the configured market proxy
 *       (default SPY). Beta comes from the same {@link EquityMetrics} blob. Null when too few
 *       residuals resolve ({@literal <} {@value #MIN_RESIDUALS}).</li>
 *   <li><b>freeFloatProxyMillions</b> — a deliberately COARSE proxy (NOT true free float):
 *       Finnhub {@code shareOutstanding} (millions of shares) &times; the latest close, i.e. an
 *       approximate market value in USD millions. Labelled a proxy so the prompt never quotes it as
 *       precise. Null when shares or a price are missing.</li>
 *   <li><b>passiveAumTrackingBillions</b> — a per-index CONFIG CONSTANT (not a feed) for the assets
 *       tracking the index, in USD billions. Surfaced in the snapshot for completeness and used as
 *       an input to {@code demandToAdvRatioEstimate}. Null for an unconfigured index.</li>
 *   <li><b>demandToAdvRatioEstimate</b> — a coarse "days of average volume the passive complex must
 *       buy" estimate: {@code (passiveAumUsd * weightProxy) / adv}, where
 *       {@code weightProxy = freeFloatProxyUsd / indexMarketCapUsd} (both coarse constants/proxies).
 *       Every input is an estimate or proxy, so the number is qualitative-only. Null when any input
 *       is missing or non-positive.</li>
 *   <li><b>confounders</b> — dilution / M&amp;A / restatement / guidance-cut / investigation flags
 *       from company news since the announcement date, via the reused {@link ConfounderScreen} (the
 *       same screen the echo hunter uses). Never throws; empty = clean.</li>
 * </ul>
 *
 * <p><b>Source-down signalling.</b> The single strict source is the price feed
 * ({@link AgoraMarketData#dailyOhlcHistory}), which raises {@link MarketDataException}. An
 * availability outage ({@link MarketDataException.Kind#UNAVAILABLE}) PROPAGATES — deliberately not
 * caught here — so {@link IndexEventEnricher} can apply its {@code markIfSourceDown} short-circuit
 * and skip the price source for the rest of the batch. A symbol-specific miss (no bars) is NOT an
 * outage: it degrades the price-derived fields to null without throwing. Market cap, share count and
 * confounders all flow through swallowing facades (null/unavailable on failure) and can never trip
 * the batch guard.
 */
@Component
public class IndexDemandSnapshotter {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int ADV_LOOKBACK = 20;
    /** Minimum residuals before an idiosyncratic-vol estimate is meaningful. */
    private static final int MIN_RESIDUALS = 20;
    private static final BigDecimal USD_PER_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal USD_PER_BILLION = BigDecimal.valueOf(1_000_000_000L);

    private final AgoraMarketData marketData;
    private final EquityMetricsExtractor equityMetrics;
    private final AgoraCompanyData companyData;
    private final ConfounderScreen confounderScreen;
    private final String marketProxy;
    private final int idioVolLookbackDays;
    private final Map<String, Double> passiveAumByIndex;
    private final Map<String, Double> indexMarketCapByIndex;

    public IndexDemandSnapshotter(
            AgoraMarketData marketData,
            EquityMetricsExtractor equityMetrics,
            AgoraCompanyData companyData,
            ConfounderScreen confounderScreen,
            @Value("${dracul.strigoi.index.market-proxy:SPY}") String marketProxy,
            @Value("${dracul.strigoi.index.idio-vol-lookback-days:90}") int idioVolLookbackDays,
            @Value("${dracul.strigoi.index.passive-aum-sp500-billions:11500}") double aumSp500,
            @Value("${dracul.strigoi.index.passive-aum-russell1000-billions:700}") double aumR1000,
            @Value("${dracul.strigoi.index.passive-aum-russell2000-billions:350}") double aumR2000,
            @Value("${dracul.strigoi.index.index-market-cap-sp500-billions:50000}") double capSp500,
            @Value("${dracul.strigoi.index.index-market-cap-russell1000-billions:57000}") double capR1000,
            @Value("${dracul.strigoi.index.index-market-cap-russell2000-billions:3500}") double capR2000) {
        this.marketData = marketData;
        this.equityMetrics = equityMetrics;
        this.companyData = companyData;
        this.confounderScreen = confounderScreen;
        this.marketProxy = marketProxy;
        this.idioVolLookbackDays = idioVolLookbackDays;
        this.passiveAumByIndex = Map.of("sp500", aumSp500, "russell1000", aumR1000, "russell2000", aumR2000);
        this.indexMarketCapByIndex = Map.of("sp500", capSp500, "russell1000", capR1000, "russell2000", capR2000);
    }

    /** ANNOUNCED-stage demand/liquidity snapshot. All monetary proxy fields are USD millions unless
     *  noted; every field degrades to null independently. {@code available} is true when any
     *  price-derived field resolved. */
    public record IndexDemandSnapshot(
            BigDecimal adv,
            Double marketCap,
            Long avgVolume20d,
            Double idiosyncraticVol,
            Double freeFloatProxyMillions,
            Double passiveAumTrackingBillions,
            Double demandToAdvRatioEstimate,
            List<String> confounders,
            boolean available) {

        static IndexDemandSnapshot unavailable() {
            return new IndexDemandSnapshot(null, null, null, null, null, null, null, List.of(), false);
        }
    }

    /**
     * @param symbol           the constituent ticker.
     * @param indexName        the tracked index ({@code sp500}/{@code russell1000}/{@code russell2000}).
     * @param announcementDate the confounder-screen "since" anchor; null screens from today.
     */
    public IndexDemandSnapshot snapshot(String symbol, String indexName, LocalDate announcementDate) {
        // Price source (strict): one generous fetch covering both the 20-day ADV window and the
        // idiosyncratic-vol lookback. UNAVAILABLE propagates for the batch guard; empty bars (a bad
        // symbol) degrade the price-derived fields to null.
        int window = Math.max(30, idioVolLookbackDays * 2);
        List<OhlcBar> stockBars = marketData.dailyOhlcHistory(symbol, window);

        BigDecimal adv = null;
        Long avgVolume20d = null;
        BigDecimal lastClose = null;
        if (!stockBars.isEmpty()) {
            lastClose = stockBars.get(stockBars.size() - 1).close();
        }
        if (stockBars.size() >= ADV_LOOKBACK) {
            List<OhlcBar> recent = stockBars.subList(stockBars.size() - ADV_LOOKBACK, stockBars.size());
            BigDecimal dollarSum = BigDecimal.ZERO;
            long volSum = 0;
            for (OhlcBar b : recent) {
                dollarSum = dollarSum.add(b.close().multiply(BigDecimal.valueOf(b.volume())));
                volSum += b.volume();
            }
            adv = dollarSum.divide(BigDecimal.valueOf(ADV_LOOKBACK), 0, RoundingMode.HALF_UP);
            avgVolume20d = volSum / ADV_LOOKBACK;
        }

        // Market cap + beta + share count from the swallowing Finnhub facades.
        EquityMetrics em = equityMetrics.metricsWithoutSector(symbol);
        Double marketCap = em.available() ? em.marketCap() : null;
        Double beta = em.available() ? em.beta() : null;

        // Idiosyncratic vol: stddev of the last ~idioVolLookback daily residuals vs the market proxy.
        Double idiosyncraticVol = idiosyncraticVol(stockBars, beta);

        // Free-float PROXY = shares outstanding (millions) x latest close.
        Double freeFloatProxyMillions = freeFloatProxyMillions(symbol, lastClose);

        Double passiveAum = passiveAumByIndex.get(indexName);
        Double demandToAdvRatioEstimate =
                demandToAdvRatioEstimate(adv, freeFloatProxyMillions, passiveAum, indexName);

        List<String> confounders = confounderScreen.confounders(
                symbol, announcementDate == null ? LocalDate.now() : announcementDate);

        boolean available = adv != null || idiosyncraticVol != null || freeFloatProxyMillions != null;
        return new IndexDemandSnapshot(adv, marketCap, avgVolume20d, idiosyncraticVol,
                freeFloatProxyMillions, passiveAum, demandToAdvRatioEstimate, confounders, available);
    }

    /** Sample stddev of the last ~idioVolLookback daily residual returns; null when too few resolve. */
    private Double idiosyncraticVol(List<OhlcBar> stockBars, Double beta) {
        // Market-proxy history: same strict price source, so an outage here propagates too.
        List<OhlcBar> marketBars = marketData.dailyOhlcHistory(marketProxy, Math.max(30, idioVolLookbackDays * 2));
        List<BigDecimal> residuals = MarketSignalService.residualReturns(stockBars, marketBars, beta);
        if (residuals.size() < MIN_RESIDUALS) return null;
        List<BigDecimal> window = residuals.size() > idioVolLookbackDays
                ? residuals.subList(residuals.size() - idioVolLookbackDays, residuals.size())
                : residuals;
        return sampleStdDev(window);
    }

    /** Sample stddev (n-1) of a residual-return window. Null on degenerate input. */
    private static Double sampleStdDev(List<BigDecimal> values) {
        int n = values.size();
        if (n < 2) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(n), MC);
        BigDecimal sq = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            sq = sq.add(d.multiply(d, MC));
        }
        BigDecimal variance = sq.divide(BigDecimal.valueOf(n - 1L), MC);
        return Math.sqrt(variance.doubleValue());
    }

    /** Finnhub shares outstanding (millions) x latest close -> coarse market-value proxy (USD millions). */
    private Double freeFloatProxyMillions(String symbol, BigDecimal lastClose) {
        if (lastClose == null || lastClose.signum() <= 0) return null;
        JsonNode metrics = companyData.fundamentals(symbol);
        if (metrics == null || !metrics.isObject()) return null;
        JsonNode shares = metrics.path("shareOutstanding");
        if (!shares.isNumber()) return null;
        double sharesMillions = shares.asDouble();
        if (sharesMillions <= 0) return null;
        return BigDecimal.valueOf(sharesMillions).multiply(lastClose, MC).doubleValue();
    }

    /** Coarse "days of ADV of forced passive demand" estimate; every input is a proxy/constant. */
    private Double demandToAdvRatioEstimate(BigDecimal adv, Double freeFloatProxyMillions,
                                            Double passiveAumBillions, String indexName) {
        if (adv == null || adv.signum() <= 0) return null;
        if (freeFloatProxyMillions == null || freeFloatProxyMillions <= 0) return null;
        if (passiveAumBillions == null || passiveAumBillions <= 0) return null;
        Double indexCapBillions = indexMarketCapByIndex.get(indexName);
        if (indexCapBillions == null || indexCapBillions <= 0) return null;

        BigDecimal freeFloatUsd = BigDecimal.valueOf(freeFloatProxyMillions).multiply(USD_PER_MILLION, MC);
        BigDecimal indexCapUsd = BigDecimal.valueOf(indexCapBillions).multiply(USD_PER_BILLION, MC);
        BigDecimal passiveAumUsd = BigDecimal.valueOf(passiveAumBillions).multiply(USD_PER_BILLION, MC);

        BigDecimal weight = freeFloatUsd.divide(indexCapUsd, MC);
        BigDecimal forcedDemandUsd = passiveAumUsd.multiply(weight, MC);
        return forcedDemandUsd.divide(adv, MC).doubleValue();
    }
}
