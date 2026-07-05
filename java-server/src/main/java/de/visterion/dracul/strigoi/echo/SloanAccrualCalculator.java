package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Sloan (1996) accrual ratio over Agora concept series:
 * {@code (NetIncomeLoss - NetCashProvidedByUsedInOperatingActivities) / Assets}, using the most
 * recent annual (350-380d) income/cash-flow durations and the latest total-assets instant.
 * Relocated from the deleted EdgarFundamentals adapter — fetch is Agora's, interpretation is
 * Dracul's. Graceful: any missing concept / parse gap → {@link AccrualMetrics#unavailable()}.
 */
@Component
public class SloanAccrualCalculator {

    private static final Logger log = LoggerFactory.getLogger(SloanAccrualCalculator.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long MIN_ANNUAL_DAYS = 350;
    private static final long MAX_ANNUAL_DAYS = 380;

    private final AgoraFilings filings;

    public SloanAccrualCalculator(AgoraFilings filings) { this.filings = filings; }

    private record Dated(LocalDate end, BigDecimal value) {}

    public AccrualMetrics accruals(String symbol) {
        try {
            Dated netIncome = latestAnnualDuration(filings.concept(symbol, "NetIncomeLoss"));
            Dated opCashFlow = latestAnnualDuration(
                    filings.concept(symbol, "NetCashProvidedByUsedInOperatingActivities"));
            BigDecimal assets = latestInstant(filings.concept(symbol, "Assets"));

            if (netIncome == null || opCashFlow == null || assets == null || assets.signum() == 0
                    || !netIncome.end().equals(opCashFlow.end())) {  // both flows must cover the same fiscal period
                return AccrualMetrics.unavailable();
            }
            BigDecimal ratio = netIncome.value().subtract(opCashFlow.value())
                    .divide(assets, MC).setScale(6, RoundingMode.HALF_UP);
            return new AccrualMetrics(ratio, true);
        } catch (Exception e) {
            log.debug("accruals failed for {}: {}", symbol, e.getMessage());
            return AccrualMetrics.unavailable();
        }
    }

    /** Most recent ~annual (350-380d) duration point, by period end; null if none. */
    private static Dated latestAnnualDuration(ConceptSeries series) {
        LocalDate bestEnd = null;
        BigDecimal bestVal = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.periodEnd() == null || p.value() == null) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
            if (bestEnd == null || p.periodEnd().isAfter(bestEnd)) {
                bestEnd = p.periodEnd();
                bestVal = p.value();
            }
        }
        return bestVal == null ? null : new Dated(bestEnd, bestVal);
    }

    /** Most recent instant point (no periodStart), by end; null if none. */
    private static BigDecimal latestInstant(ConceptSeries series) {
        LocalDate bestEnd = null;
        BigDecimal bestVal = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodEnd() == null || p.value() == null) continue;
            if (p.periodStart() != null) continue;   // instant facts only
            if (bestEnd == null || p.periodEnd().isAfter(bestEnd)) {
                bestEnd = p.periodEnd();
                bestVal = p.value();
            }
        }
        return bestVal;
    }
}
