package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Classic Altman Z-Score (1968) over Agora XBRL concept series:
 * {@code Z = 1.2·X1 + 1.4·X2 + 3.3·X3 + 0.6·X4 + 1.0·X5} with
 * X1 = working capital / total assets, X2 = retained earnings / total assets,
 * X3 = EBIT / total assets, X4 = market value of equity / total liabilities,
 * X5 = sales / total assets.
 *
 * <p>Input conventions (documented choices):
 * <ul>
 *   <li>Balance-sheet inputs (Assets, AssetsCurrent, LiabilitiesCurrent, Liabilities,
 *       RetainedEarningsAccumulatedDeficit) are the LATEST reported instants, and every one
 *       of them must share the exact balance-sheet date of the latest Assets instant —
 *       mixing balance-sheet dates (e.g. a filer that stopped reporting a concept years
 *       ago) makes the score unavailable rather than silently stale.</li>
 *   <li>Flow inputs (EBIT ≈ {@code OperatingIncomeLoss}, sales = {@code Revenues} with the
 *       same fallback tags the F-score uses) are the latest reported FISCAL-YEAR durations
 *       (350–380 days, matching {@code SloanAccrualCalculator}) — not TTM — and both must
 *       end on the same fiscal-year end; a revenue tag whose annual points do not reach that
 *       end (stale pre-tag-switch history) falls through to the next tag of the chain.</li>
 *   <li>Market value of equity is Finnhub's {@code marketCapitalization}, which is quoted
 *       in USD MILLIONS while every XBRL value is raw USD: it is converted (×10⁶) here.</li>
 * </ul>
 *
 * <p>No partial Z: the score is only meaningful with ALL five ratios, so the first missing
 * input yields {@link AltmanZ#unavailable()} and stops fetching further concepts (each fetch
 * is a remote Agora call). {@link de.visterion.dracul.marketdata.AgoraUnavailableException}
 * from the strict concept fetch is deliberately NOT caught here — the enrichment service
 * uses it to short-circuit a down source for the rest of the batch.
 */
@Component
public class AltmanZCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long MIN_ANNUAL_DAYS = 350;
    private static final long MAX_ANNUAL_DAYS = 380;
    private static final BigDecimal USD_PER_MILLION = BigDecimal.valueOf(1_000_000L);

    private static final String[] REVENUE_TAGS = {
            "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet"};

    private final AgoraFilings filings;

    public AltmanZCalculator(AgoraFilings filings) { this.filings = filings; }

    /** Z-score of one symbol; {@code zScore} is scale-2 and null unless {@code available}. */
    public record AltmanZ(BigDecimal zScore, boolean available) {
        public static AltmanZ unavailable() { return new AltmanZ(null, false); }
    }

    private record Dated(LocalDate end, BigDecimal value) {}

    /**
     * @param marketCapMillions Finnhub market cap in USD MILLIONS (converted to USD here);
     *                          null or non-positive → unavailable without any remote call.
     */
    public AltmanZ zScore(String symbol, Double marketCapMillions) {
        if (marketCapMillions == null || marketCapMillions <= 0) return AltmanZ.unavailable();

        Dated assets = latestInstant(filings.conceptStrict(symbol, "Assets"));
        if (assets == null || assets.value().signum() <= 0) return AltmanZ.unavailable();

        BigDecimal currentAssets = instantAt(assets.end(), filings.conceptStrict(symbol, "AssetsCurrent"));
        if (currentAssets == null) return AltmanZ.unavailable();
        BigDecimal currentLiabilities = instantAt(assets.end(), filings.conceptStrict(symbol, "LiabilitiesCurrent"));
        if (currentLiabilities == null) return AltmanZ.unavailable();
        BigDecimal liabilities = instantAt(assets.end(), filings.conceptStrict(symbol, "Liabilities"));
        if (liabilities == null || liabilities.signum() <= 0) return AltmanZ.unavailable();
        BigDecimal retainedEarnings = instantAt(assets.end(),
                filings.conceptStrict(symbol, "RetainedEarningsAccumulatedDeficit"));
        if (retainedEarnings == null) return AltmanZ.unavailable();

        Dated ebit = latestAnnualDuration(filings.conceptStrict(symbol, "OperatingIncomeLoss"));
        if (ebit == null) return AltmanZ.unavailable();
        BigDecimal revenue = firstAnnualRevenueAt(symbol, ebit.end());
        if (revenue == null) return AltmanZ.unavailable();

        BigDecimal totalAssets = assets.value();
        BigDecimal marketValueEquity = BigDecimal.valueOf(marketCapMillions).multiply(USD_PER_MILLION);

        BigDecimal x1 = currentAssets.subtract(currentLiabilities).divide(totalAssets, MC);
        BigDecimal x2 = retainedEarnings.divide(totalAssets, MC);
        BigDecimal x3 = ebit.value().divide(totalAssets, MC);
        BigDecimal x4 = marketValueEquity.divide(liabilities, MC);
        BigDecimal x5 = revenue.divide(totalAssets, MC);

        BigDecimal z = x1.multiply(new BigDecimal("1.2"), MC)
                .add(x2.multiply(new BigDecimal("1.4"), MC), MC)
                .add(x3.multiply(new BigDecimal("3.3"), MC), MC)
                .add(x4.multiply(new BigDecimal("0.6"), MC), MC)
                .add(x5, MC)
                .setScale(2, RoundingMode.HALF_UP);
        return new AltmanZ(z, true);
    }

    /** Sales for the fiscal year ending exactly at {@code fyEnd} (the EBIT fiscal-year end),
     *  from the first revenue tag of the fallback chain that has an annual point on that end.
     *  The FY match must happen PER TAG, not after picking the first tag with any annual
     *  point: EDGAR keeps historical datapoints forever, so a tag switcher (e.g. the large
     *  ASC-606 cohort that moved from {@code Revenues} to
     *  {@code RevenueFromContractWithCustomerExcludingAssessedTax}) still carries stale
     *  annual points under the old tag — those must fall through to the tag that is actually
     *  still filed instead of making the Z permanently unavailable. Each fallback costs one
     *  more remote call, so the primary tag is tried first. */
    private BigDecimal firstAnnualRevenueAt(String symbol, LocalDate fyEnd) {
        for (String tag : REVENUE_TAGS) {
            BigDecimal v = annualDurationAt(fyEnd, filings.conceptStrict(symbol, tag));
            if (v != null) return v;
        }
        return null;
    }

    /** Annual (350-380d) duration point ending exactly at {@code end}; null if none. */
    private static BigDecimal annualDurationAt(LocalDate end, ConceptSeries series) {
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.value() == null || !end.equals(p.periodEnd())) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days >= MIN_ANNUAL_DAYS && days <= MAX_ANNUAL_DAYS) return p.value();
        }
        return null;
    }

    /** Most recent ~annual (350-380d) duration point, by period end; null if none. */
    private static Dated latestAnnualDuration(ConceptSeries series) {
        Dated best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.periodEnd() == null || p.value() == null) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
            if (best == null || p.periodEnd().isAfter(best.end())) best = new Dated(p.periodEnd(), p.value());
        }
        return best;
    }

    /** Most recent instant point (no periodStart), by end; null if none. */
    private static Dated latestInstant(ConceptSeries series) {
        Dated best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodEnd() == null || p.value() == null) continue;
            if (p.periodStart() != null) continue;   // instant facts only
            if (best == null || p.periodEnd().isAfter(best.end())) best = new Dated(p.periodEnd(), p.value());
        }
        return best;
    }

    /** Instant point at exactly {@code end} (the anchor balance-sheet date); null if none. */
    private static BigDecimal instantAt(LocalDate end, ConceptSeries series) {
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() != null || p.value() == null) continue;   // instant facts only
            if (end.equals(p.periodEnd())) return p.value();
        }
        return null;
    }
}
