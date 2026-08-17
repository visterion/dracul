package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
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
 * Uses {@link AgoraFilings#conceptStrict} rather than the swallowing {@link
 * AgoraFilings#concept}: an Agora/EDGAR outage now reaches this class as a thrown {@link
 * AgoraUnavailableException} instead of silently coming back as an empty series
 * indistinguishable from "concept not filed" — see {@link #fetchConcept} for the scope-aware
 * logging that replaces what {@code concept()} used to do internally.
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
        ConceptSeries niSeries = fetchConcept(symbol, "NetIncomeLoss");
        if (niSeries == null) return AccrualMetrics.unavailable();
        ConceptSeries ocfSeries = fetchConcept(symbol, "NetCashProvidedByUsedInOperatingActivities");
        if (ocfSeries == null) return AccrualMetrics.unavailable();
        ConceptSeries assetsSeries = fetchConcept(symbol, "Assets");
        if (assetsSeries == null) return AccrualMetrics.unavailable();

        try {
            Dated netIncome = latestAnnualDuration(niSeries);
            Dated opCashFlow = latestAnnualDuration(ocfSeries);
            BigDecimal assets = latestInstant(assetsSeries);

            if (netIncome == null || opCashFlow == null || assets == null || assets.signum() == 0
                    || !netIncome.end().equals(opCashFlow.end())) {  // both flows must cover the same fiscal period
                return AccrualMetrics.unavailable();
            }
            BigDecimal ratio = netIncome.value().subtract(opCashFlow.value())
                    .divide(assets, MC).setScale(6, RoundingMode.HALF_UP);
            return new AccrualMetrics(ratio, true);
        } catch (RuntimeException e) {
            // A malformed datapoint / unexpected null is a parsing/shape problem, not a source
            // outage; keep it at DEBUG as before.
            log.debug("accruals failed for {}: {}", symbol, e.getMessage());
            return AccrualMetrics.unavailable();
        }
    }

    /**
     * Fetches one concept via {@link AgoraFilings#conceptStrict} instead of the swallowing {@link
     * AgoraFilings#concept}, so an Agora/EDGAR outage reaches this class instead of silently
     * coming back as an empty series indistinguishable from "concept not filed" — {@code
     * concept()} used to absorb it and log it as {@code agora source unavailable} on {@code
     * AgoraFilings}' own behalf, which this switch removes, so it is re-emitted here.
     *
     * <p>Branches on {@link AgoraUnavailableException.Scope} exactly like {@code AgoraFilings}'
     * own {@code logSwallowed}: {@code Scope.SOURCE} (Agora never answered) is a genuine outage,
     * worth the {@code agora source unavailable} prefix; {@code Scope.REQUEST} (Agora answered
     * with an error envelope about this one request — e.g. an unresolvable CIK) is evidence about
     * the request, not the source, and gets {@code agora request failed} instead — folding both
     * into one prefix would make the outage prefix worthless for alarming. The subject is {@code
     * symbol:tag}, not just {@code symbol}, because {@code accruals} makes three of these calls
     * per symbol and the tag is the only way to tell which one failed.
     *
     * @return the series, or {@code null} (having already logged) on any Agora failure.
     */
    private ConceptSeries fetchConcept(String symbol, String tag) {
        try {
            return filings.conceptStrict(symbol, tag);
        } catch (AgoraUnavailableException e) {
            String prefix = e.scope() == AgoraUnavailableException.Scope.SOURCE
                    ? "agora source unavailable"
                    : "agora request failed";
            log.warn("{}: tool=get_company_concept subject={}:{} — {}", prefix, symbol, tag, e.getMessage());
            return null;
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
