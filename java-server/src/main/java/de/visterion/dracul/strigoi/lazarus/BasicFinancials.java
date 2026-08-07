package de.visterion.dracul.strigoi.lazarus;

/**
 * Subset of Finnhub's basic-financials ("metric") object. All numeric fields are boxed
 * Double so an absent metric (null) is distinguishable from a real 0. Percent
 * metrics (ROA, margins, growth) are in Finnhub's native percent units;
 * {@code marketCap} is in MILLIONS of the reporting currency (USD for US names, the
 * instrument's reporting currency for non-US names — convert before mixing with raw
 * concept values). {@code reportingCurrency} is the ISO-4217 code those reporting-currency
 * metrics are expressed in — present only on the non-US Agora path (null for US), and used
 * as the Altman-Z X4 currency-consistency guard against the concept liabilities' unit.
 *
 * <p>{@code week52RangeUnavailable} carries Agora's group-scoped marker
 * ({@code "52WeekRange": {"available": false, "error": "..."}}), present only when the 52-week
 * OHLC SOURCE failed. It is the difference between "this instrument has no 52-week low" and
 * "we asked and could not find out" — both of which reach us as a null {@code week52Low}.
 */
public record BasicFinancials(
        Double week52Low,
        Double week52High,
        Double roaTtm,
        Double currentRatio,
        Double debtToEquity,
        Double grossMargin,
        Double netMargin,
        Double revenueGrowthYoy,
        Double epsGrowthYoy,
        Double priceToBook,
        Double peTtm,
        Double fcfPerShare,
        Double marketCap,
        String reportingCurrency,
        boolean week52RangeUnavailable
) {
    /**
     * Back-compat convenience with no 52-week-range failure marker (defaults false): the
     * absence of the marker is exactly what Agora emits for a healthy fetch, so "not marked"
     * is the correct default rather than a lie.
     */
    public BasicFinancials(
            Double week52Low, Double week52High, Double roaTtm, Double currentRatio,
            Double debtToEquity, Double grossMargin, Double netMargin, Double revenueGrowthYoy,
            Double epsGrowthYoy, Double priceToBook, Double peTtm, Double fcfPerShare,
            Double marketCap, String reportingCurrency) {
        this(week52Low, week52High, roaTtm, currentRatio, debtToEquity, grossMargin, netMargin,
                revenueGrowthYoy, epsGrowthYoy, priceToBook, peTtm, fcfPerShare, marketCap,
                reportingCurrency, false);
    }

    /** Back-compat convenience for US callers/tests with no reporting currency (defaults null). */
    public BasicFinancials(
            Double week52Low, Double week52High, Double roaTtm, Double currentRatio,
            Double debtToEquity, Double grossMargin, Double netMargin, Double revenueGrowthYoy,
            Double epsGrowthYoy, Double priceToBook, Double peTtm, Double fcfPerShare,
            Double marketCap) {
        this(week52Low, week52High, roaTtm, currentRatio, debtToEquity, grossMargin, netMargin,
                revenueGrowthYoy, epsGrowthYoy, priceToBook, peTtm, fcfPerShare, marketCap, null);
    }
}
