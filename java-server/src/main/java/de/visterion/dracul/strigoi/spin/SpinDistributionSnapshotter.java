package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * DISTRIBUTED-stage enrichment: the size/forced-selling read that only exists once the spin-off
 * trades. Pure and fail-soft.
 *
 * <ul>
 *   <li><b>spincoMarketCapMillions / parentMarketCapMillions</b> — Finnhub {@code marketCapitalization}
 *       (native USD millions) via {@link EquityMetricsExtractor}, keyed on the spin-co's ticker and,
 *       when known, the parent's. The parent's identity comes from the term-sheet / company-name
 *       context resolved upstream (E3); when it cannot be resolved cleanly the parent symbol is blank
 *       and the parent fields stay null. Cross-candidate de-duplication (several spin-cos sharing one
 *       parent) is an E3 batching concern — this pure per-candidate function just resolves the symbol
 *       it is handed.</li>
 *   <li><b>sizeRatio</b> = spincoMarketCap / parentMarketCap (scale-4), the small-spin-off effect;
 *       null unless both caps are present and the parent's is positive.</li>
 *   <li><b>daysSinceDistribution</b> = whole days from the distribution date to {@code today}.</li>
 *   <li><b>postSpinInsiderBuying</b> — whether any reporting owner made an open-market purchase
 *       (Form-4 code {@code P}) on or after the distribution date, derived from ONE
 *       {@link AgoraFilings#ownerHistoryStrict(String)} call.</li>
 * </ul>
 *
 * <p><b>Source-down signalling.</b> The two sources have deliberately different contracts, matching
 * the facades the insider hunter already uses. Market caps go through {@link EquityMetricsExtractor},
 * which is built on the swallowing {@code fundamentals()}/{@code profile()} — an outage is absorbed
 * as {@link EquityMetrics#unavailable()} (fields null, {@code marketCapAvailable} false, never a
 * throw), so it can never trip the batch guard. The owner history uses the STRICT variant, so an
 * {@link de.visterion.dracul.marketdata.AgoraUnavailableException} PROPAGATES (not caught here) and
 * lets the E3 reconciler apply {@code markIfSourceDown}; a successful call that simply returns no
 * qualifying purchase yields {@code postSpinInsiderBuying=false} with {@code insiderAvailable=true}.
 */
@Component
public class SpinDistributionSnapshotter {

    private final EquityMetricsExtractor equityMetrics;
    private final AgoraFilings filings;

    public SpinDistributionSnapshotter(EquityMetricsExtractor equityMetrics, AgoraFilings filings) {
        this.equityMetrics = equityMetrics;
        this.filings = filings;
    }

    /** Post-distribution size / forced-selling snapshot. Market-cap fields are Finnhub USD millions. */
    public record SpinDistributionSnapshot(
            Double spincoMarketCapMillions,
            Double parentMarketCapMillions,
            Double sizeRatio,
            Integer daysSinceDistribution,
            Boolean postSpinInsiderBuying,
            boolean marketCapAvailable,
            boolean insiderAvailable) {

        static SpinDistributionSnapshot unavailable() {
            return new SpinDistributionSnapshot(null, null, null, null, null, false, false);
        }
    }

    /**
     * @param spincoSymbol     the now-trading spin-off ticker.
     * @param parentSymbol     the parent ticker, or blank/null when it could not be resolved.
     * @param distributionDate the distribution (first-trade) date; null degrades the calendar and
     *                         insider fields to null.
     * @param today            the reconciliation date (passed in for deterministic testing).
     */
    public SpinDistributionSnapshot snapshot(String spincoSymbol, String parentSymbol,
                                             LocalDate distributionDate, LocalDate today) {
        Double spincoCap = marketCapMillions(spincoSymbol);
        Double parentCap = marketCapMillions(parentSymbol);
        boolean marketCapAvailable = spincoCap != null;

        Double sizeRatio = null;
        if (spincoCap != null && parentCap != null && parentCap > 0) {
            sizeRatio = BigDecimal.valueOf(spincoCap)
                    .divide(BigDecimal.valueOf(parentCap), 4, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        Integer daysSinceDistribution = (distributionDate == null || today == null)
                ? null
                : (int) ChronoUnit.DAYS.between(distributionDate, today);

        Boolean postSpinInsiderBuying = null;
        boolean insiderAvailable = false;
        if (spincoSymbol != null && !spincoSymbol.isBlank() && distributionDate != null) {
            Form4OwnerHistory history = filings.ownerHistoryStrict(spincoSymbol); // propagates outage
            postSpinInsiderBuying = hasOpenMarketPurchaseSince(history, distributionDate);
            insiderAvailable = true;
        }

        return new SpinDistributionSnapshot(spincoCap, parentCap, sizeRatio,
                daysSinceDistribution, postSpinInsiderBuying, marketCapAvailable, insiderAvailable);
    }

    /** Finnhub market cap (USD millions) for a ticker; null when the ticker is blank or the
     *  swallowing metrics lookup came back unavailable. */
    private Double marketCapMillions(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        EquityMetrics m = equityMetrics.metricsWithoutSector(symbol);
        return m.available() ? m.marketCap() : null;
    }

    /** True when any reporting owner has an open-market purchase (code {@code P}) dated on or after
     *  the distribution date. */
    private static boolean hasOpenMarketPurchaseSince(Form4OwnerHistory history, LocalDate since) {
        for (Form4OwnerHistory.Owner o : history.owners()) {
            for (Form4OwnerHistory.Transaction t : o.transactions()) {
                if (t.transactionDate() != null
                        && "P".equalsIgnoreCase(t.code())
                        && !t.transactionDate().isBefore(since)) {
                    return true;
                }
            }
        }
        return false;
    }
}
