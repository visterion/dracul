package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Annotates screened index-inclusion candidates with liquidity/size metrics (average daily
 *  dollar volume, average daily share volume, market cap) so the LLM can judge the forced-buying
 *  magnitude against real numbers instead of guessing. Fail-soft: any lookup failure degrades
 *  that one candidate's fields to null, never the run. Bounded to {@link #MAX} candidates. */
@Component
public class IndexEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(IndexEnrichmentService.class);
    private static final int MAX = 25;
    private static final int LOOKBACK = 20;
    private static final int HISTORY_DAYS = 30;

    private final AgoraMarketData marketData;
    private final EquityMetricsExtractor equityMetrics;

    public IndexEnrichmentService(AgoraMarketData marketData, EquityMetricsExtractor equityMetrics) {
        this.marketData = marketData;
        this.equityMetrics = equityMetrics;
    }

    public List<EnrichedIndexCandidate> enrich(List<IndexCandidate> candidates) {
        return candidates.stream().limit(MAX).map(this::enrichOne).toList();
    }

    private EnrichedIndexCandidate enrichOne(IndexCandidate c) {
        BigDecimal adv = null;
        Long avgVol = null;
        Double marketCap = null;

        try {
            List<OhlcBar> bars = marketData.dailyOhlcHistory(c.symbol(), HISTORY_DAYS);
            if (bars.size() >= LOOKBACK) {
                List<OhlcBar> recent = bars.subList(bars.size() - LOOKBACK, bars.size());
                BigDecimal dollarSum = BigDecimal.ZERO;
                long volSum = 0;
                for (OhlcBar b : recent) {
                    dollarSum = dollarSum.add(b.close().multiply(BigDecimal.valueOf(b.volume())));
                    volSum += b.volume();
                }
                adv = dollarSum.divide(BigDecimal.valueOf(LOOKBACK), 0, RoundingMode.HALF_UP);
                avgVol = volSum / LOOKBACK;
            }
        } catch (RuntimeException e) {
            log.debug("index enrichment: ohlc history unavailable for {}: {}", c.symbol(), e.getMessage());
        }

        try {
            EquityMetrics em = equityMetrics.metrics(c.symbol());
            if (em.available()) marketCap = em.marketCap();
        } catch (RuntimeException e) {
            log.debug("index enrichment: equity metrics unavailable for {}: {}", c.symbol(), e.getMessage());
        }

        boolean any = adv != null || avgVol != null || marketCap != null;
        return new EnrichedIndexCandidate(c.symbol(), c.companyName(), c.dateAdded(),
                adv, marketCap, avgVol, any);
    }
}
