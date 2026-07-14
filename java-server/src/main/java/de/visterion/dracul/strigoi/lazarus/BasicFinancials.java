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
        String reportingCurrency
) {
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
