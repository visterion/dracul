package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Classic Altman Z-Score (1968) over Agora XBRL concept series:
 * {@code Z = 1.2·X1 + 1.4·X2 + 3.3·X3 + 0.6·X4 + 1.0·X5} with
 * X1 = working capital / total assets, X2 = retained earnings / total assets,
 * X3 = EBIT / total assets, X4 = market value of equity / total liabilities,
 * X5 = sales / total assets.
 *
 * <p>All XBRL inputs are pulled in ONE bulk {@link AgoraFilings#companyFactsStrict} call
 * (previously up to eight sequential {@code get_company_concept} calls) and every ratio is then
 * computed from the in-memory map — no fetch is triggered per helper.
 *
 * <p>Input conventions (documented choices):
 * <ul>
 *   <li>Balance-sheet inputs (Assets, AssetsCurrent, LiabilitiesCurrent, Liabilities,
 *       RetainedEarningsAccumulatedDeficit) are the LATEST reported instants, and every one
 *       of them must share the exact balance-sheet date of the latest Assets instant —
 *       mixing balance-sheet dates (e.g. a filer that stopped reporting a concept years
 *       ago) makes the score unavailable rather than silently stale.</li>
 *   <li>Liabilities-derivation fallback: many filers report {@code StockholdersEquity} but omit
 *       the standalone {@code Liabilities} tag. When the reported tag is absent or non-positive
 *       at the anchor date, liabilities are derived from the accounting identity
 *       {@code Liabilities = Assets - StockholdersEquity} at the SAME date (used only when
 *       {@code StockholdersEquity} is present and the difference is positive). When that identity
 *       is also unavailable, a second fallback sums {@code LiabilitiesCurrent +
 *       LiabilitiesNoncurrent} at the same date.</li>
 *   <li>Flow inputs (EBIT ≈ {@code OperatingIncomeLoss}, sales = {@code Revenues} with the
 *       same fallback tags the F-score uses) are the latest reported FISCAL-YEAR durations
 *       (350–380 days, matching {@code SloanAccrualCalculator}) — not TTM — and both must
 *       end on the same fiscal-year end; a revenue tag whose annual points do not reach that
 *       end (stale pre-tag-switch history) falls through to the next tag of the chain.</li>
 *   <li>Restatement (latest-filed) dedup: EDGAR keeps every filing forever, so a single
 *       (periodStart, periodEnd) can appear multiple times — original plus a later
 *       amendment/restatement. Among points matching the same period the helpers prefer the
 *       one with the GREATEST {@code filed} date (a null {@code filed} sorts oldest), so the
 *       score is deterministic across a restatement.</li>
 *   <li>Market value of equity is Finnhub's {@code marketCapitalization}, which is quoted
 *       in USD MILLIONS while every XBRL value is raw USD: it is converted (×10⁶) here.</li>
 * </ul>
 *
 * <p>No partial Z: the score is only meaningful with ALL five ratios, so the first missing
 * input yields {@link AltmanZ#unavailable()}. Since every tag arrives in the one bulk fetch,
 * there is no per-input remote call to save by bailing early — an early return simply avoids
 * pointless arithmetic. {@link de.visterion.dracul.marketdata.AgoraUnavailableException}
 * from the strict bulk fetch is deliberately NOT caught here — the enrichment service
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

    /** Every us-gaap tag the Z needs, fetched in one bulk call: balance-sheet instants, the
     *  Liabilities-derivation operands (identity primary, current+noncurrent fallback), the
     *  EBIT flow, then the revenue fallback chain. */
    private static final List<String> BULK_TAGS = List.of(
            "Assets", "AssetsCurrent", "LiabilitiesCurrent", "Liabilities",
            "LiabilitiesNoncurrent", "StockholdersEquity",
            "RetainedEarningsAccumulatedDeficit", "OperatingIncomeLoss",
            "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet");

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

        Map<String, ConceptSeries> facts = filings.companyFactsStrict(symbol, BULK_TAGS);

        Dated assets = latestInstant(series(facts, "Assets"));
        if (assets == null || assets.value().signum() <= 0) return AltmanZ.unavailable();
        LocalDate anchor = assets.end();

        BigDecimal currentAssets = instantAt(anchor, series(facts, "AssetsCurrent"));
        if (currentAssets == null) return AltmanZ.unavailable();
        BigDecimal currentLiabilities = instantAt(anchor, series(facts, "LiabilitiesCurrent"));
        if (currentLiabilities == null) return AltmanZ.unavailable();
        BigDecimal liabilities = liabilitiesAt(anchor, assets.value(), currentLiabilities, facts);
        if (liabilities == null || liabilities.signum() <= 0) return AltmanZ.unavailable();
        BigDecimal retainedEarnings = instantAt(anchor, series(facts, "RetainedEarningsAccumulatedDeficit"));
        if (retainedEarnings == null) return AltmanZ.unavailable();

        Dated ebit = latestAnnualDuration(series(facts, "OperatingIncomeLoss"));
        if (ebit == null) return AltmanZ.unavailable();
        BigDecimal revenue = firstAnnualRevenueAt(facts, ebit.end());
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

    /** Total liabilities at the anchor balance-sheet date. Prefers the reported
     *  {@code Liabilities} instant; when that is absent or non-positive, derives it from the
     *  accounting identity {@code Liabilities = Assets - StockholdersEquity} at the SAME date
     *  (used only when {@code StockholdersEquity} is present and the difference is strictly
     *  positive). When that identity is also unavailable, falls back to
     *  {@code LiabilitiesCurrent + LiabilitiesNoncurrent} at the same date (the current-liability
     *  operand is already fetched by the caller for X1, so it is passed in rather than looked up
     *  twice). Null (Z unavailable) only when none of the three sources yields a positive value. */
    private static BigDecimal liabilitiesAt(LocalDate anchor, BigDecimal assets, BigDecimal currentLiabilities,
                                             Map<String, ConceptSeries> facts) {
        BigDecimal reported = instantAt(anchor, series(facts, "Liabilities"));
        if (reported != null && reported.signum() > 0) return reported;

        BigDecimal equity = instantAt(anchor, series(facts, "StockholdersEquity"));
        if (equity != null) {
            BigDecimal derived = assets.subtract(equity);
            if (derived.signum() > 0) return derived;
        }

        BigDecimal noncurrentLiabilities = instantAt(anchor, series(facts, "LiabilitiesNoncurrent"));
        if (noncurrentLiabilities != null) {
            BigDecimal summed = currentLiabilities.add(noncurrentLiabilities);
            if (summed.signum() > 0) return summed;
        }

        return null;
    }

    /** Sales for the fiscal year ending exactly at {@code fyEnd} (the EBIT fiscal-year end),
     *  from the first revenue tag of the fallback chain that has an annual point on that end.
     *  The FY match must happen PER TAG, not after picking the first tag with any annual
     *  point: EDGAR keeps historical datapoints forever, so a tag switcher (e.g. the large
     *  ASC-606 cohort that moved from {@code Revenues} to
     *  {@code RevenueFromContractWithCustomerExcludingAssessedTax}) still carries stale
     *  annual points under the old tag — those must fall through to the tag that is actually
     *  still filed instead of making the Z permanently unavailable. All tags are already in the
     *  bulk map, so trying the whole chain costs nothing extra; the primary tag still wins. */
    private static BigDecimal firstAnnualRevenueAt(Map<String, ConceptSeries> facts, LocalDate fyEnd) {
        for (String tag : REVENUE_TAGS) {
            BigDecimal v = annualDurationAt(fyEnd, series(facts, tag));
            if (v != null) return v;
        }
        return null;
    }

    /** Bulk-map lookup: every requested tag is present as at least an empty series, but guard
     *  defensively so a stray absent key degrades to empty rather than NPEs. */
    private static ConceptSeries series(Map<String, ConceptSeries> facts, String tag) {
        ConceptSeries s = facts.get(tag);
        return s != null ? s : ConceptSeries.empty(tag);
    }

    /** Annual (350-380d) duration point ending exactly at {@code end}; among restatements of
     *  that period the greatest-{@code filed} value wins. Null if none. */
    private static BigDecimal annualDurationAt(LocalDate end, ConceptSeries series) {
        ConceptSeries.Point best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.value() == null || !end.equals(p.periodEnd())) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
            if (best == null || filedAfter(p.filed(), best.filed())) best = p;
        }
        return best == null ? null : best.value();
    }

    /** Most recent ~annual (350-380d) duration point, by period end; {@code filed} breaks a tie
     *  between two points sharing the same period end (latest-filed restatement wins). */
    private static Dated latestAnnualDuration(ConceptSeries series) {
        ConceptSeries.Point best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.periodEnd() == null || p.value() == null) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
            if (best == null || moreRecent(p, best)) best = p;
        }
        return best == null ? null : new Dated(best.periodEnd(), best.value());
    }

    /** Most recent instant point (no periodStart), by end; {@code filed} breaks a tie between
     *  two points sharing the same period end (latest-filed restatement wins). */
    private static Dated latestInstant(ConceptSeries series) {
        ConceptSeries.Point best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodEnd() == null || p.value() == null) continue;
            if (p.periodStart() != null) continue;   // instant facts only
            if (best == null || moreRecent(p, best)) best = p;
        }
        return best == null ? null : new Dated(best.periodEnd(), best.value());
    }

    /** Instant point at exactly {@code end} (the anchor balance-sheet date); among restatements
     *  of that date the greatest-{@code filed} value wins. Null if none. */
    private static BigDecimal instantAt(LocalDate end, ConceptSeries series) {
        ConceptSeries.Point best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() != null || p.value() == null) continue;   // instant facts only
            if (!end.equals(p.periodEnd())) continue;
            if (best == null || filedAfter(p.filed(), best.filed())) best = p;
        }
        return best == null ? null : best.value();
    }

    /** "Most recent" ordering: primary key is the period end, {@code filed} is the tie-breaker
     *  (only when two points share the same period end). */
    private static boolean moreRecent(ConceptSeries.Point cand, ConceptSeries.Point best) {
        int cmp = cand.periodEnd().compareTo(best.periodEnd());
        if (cmp != 0) return cmp > 0;
        return filedAfter(cand.filed(), best.filed());
    }

    /** True if {@code a} is a later filing than {@code b}. A null {@code filed} sorts oldest:
     *  it never beats a dated point, and any dated point beats it. */
    private static boolean filedAfter(LocalDate a, LocalDate b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }
}
