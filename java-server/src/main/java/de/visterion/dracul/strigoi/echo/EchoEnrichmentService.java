package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.marketdata.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Deterministic PEAD enrichment. SP1: SUE (+decile across the batch), revenue-surprise,
 *  double-beat, consecutive seasonal beats. SP2: market-adjusted announcement-CAR, abnormal
 *  volume, momentum and ADV (from OHLC vs the market proxy) plus beta/marketCap/sector.
 *  Every external lookup is wrapped so a failure degrades one field, never the whole run. */
@Component
public class EchoEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EchoEnrichmentService.class);

    private final SueEngine sueEngine;
    private final EpsHistoryPort epsHistory;
    private final MarketDataPort marketData;
    private final MarketSignalService marketSignals;
    private final EquityMetricsPort equityMetrics;
    private final String marketProxy;
    private final int historyDays;

    public EchoEnrichmentService(
            SueEngine sueEngine,
            EpsHistoryPort epsHistory,
            MarketDataPort marketData,
            MarketSignalService marketSignals,
            EquityMetricsPort equityMetrics,
            @Value("${dracul.strigoi.echo.car.market-proxy:SPY}") String marketProxy,
            @Value("${dracul.strigoi.echo.ohlc-history-days:320}") int historyDays) {
        this.sueEngine = sueEngine;
        this.epsHistory = epsHistory;
        this.marketData = marketData;
        this.marketSignals = marketSignals;
        this.equityMetrics = equityMetrics;
        this.marketProxy = marketProxy;
        this.historyDays = historyDays;
    }

    public List<EnrichedPeadCandidate> enrich(List<PeadCandidate> candidates) {
        record Partial(PeadCandidate c, Sue sue, List<QuarterlyEps> hist) {}
        List<Partial> partials = new ArrayList<>();
        List<Double> sueValues = new ArrayList<>();

        for (PeadCandidate c : candidates) {
            List<QuarterlyEps> hist;
            try {
                hist = epsHistory.quarterlyEps(c.symbol(), 16);
            } catch (Exception e) {
                hist = List.of();
            }
            Sue sue = sueEngine.timeSeriesSue(c.epsActual(), c.reportDate(), hist);
            if (sue.available() && sue.value() != null) sueValues.add(sue.value());
            partials.add(new Partial(c, sue, hist));
        }
        boolean thin = sueValues.size() < 20;

        // Market proxy OHLC fetched once for the whole batch (CAR denominator).
        List<OhlcBar> marketBars = ohlc(marketProxy);

        List<EnrichedPeadCandidate> out = new ArrayList<>();
        for (Partial p : partials) {
            PeadCandidate c = p.c();

            Integer decile = null;
            boolean approximate = p.sue().approximate();
            if (p.sue().available() && p.sue().value() != null) {
                decile = sueEngine.decile(p.sue().value(), sueValues, thin);
                approximate = thin || p.sue().approximate();
            }
            BigDecimal revSurprise = revenueSurprise(c.revenueActual(), c.revenueEstimate());
            // double beat = both EPS and revenue beat (EPS beat is already guaranteed by
            // the pre-screen, but make the semantics explicit and robust here).
            boolean doubleBeat = revSurprise != null && revSurprise.signum() > 0
                    && c.surprisePercent() != null && c.surprisePercent().signum() > 0;
            Integer consecutive = p.hist().isEmpty() ? null : sueEngine.seasonalBeatStreak(p.hist());

            EquityMetrics em = safeMetrics(c.symbol());
            List<OhlcBar> stockBars = ohlc(c.symbol());
            MarketSignals ms = marketSignals.compute(stockBars, marketBars, c.reportDate(), em.beta());

            out.add(new EnrichedPeadCandidate(
                    c.symbol(), c.companyName(), c.reportDate(),
                    ChronoUnit.DAYS.between(c.reportDate(), LocalDate.now()),
                    c.epsActual(), c.epsEstimate(), c.surprisePercent(),
                    p.sue().value(), decile, approximate, p.sue().available(),
                    revSurprise, doubleBeat, consecutive, c.currentPrice(),
                    ms.announcementCar1d(), ms.announcementCar3d(), ms.carAvailable(),
                    ms.abnormalVolume(), ms.momentum6_12m(), ms.adv(),
                    em.marketCap(), em.beta(), em.sector(), em.available()));
        }
        return out;
    }

    private List<OhlcBar> ohlc(String symbol) {
        try {
            return marketData.dailyOhlcHistory(symbol, historyDays);
        } catch (Exception e) {
            log.debug("echo enrichment: OHLC unavailable for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private EquityMetrics safeMetrics(String symbol) {
        try {
            return equityMetrics.metrics(symbol);
        } catch (Exception e) {
            log.debug("echo enrichment: equity metrics unavailable for {}: {}", symbol, e.getMessage());
            return EquityMetrics.unavailable();
        }
    }

    private static BigDecimal revenueSurprise(BigDecimal actual, BigDecimal estimate) {
        if (actual == null || estimate == null || estimate.signum() == 0) return null;
        return actual.subtract(estimate)
                .divide(estimate.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
