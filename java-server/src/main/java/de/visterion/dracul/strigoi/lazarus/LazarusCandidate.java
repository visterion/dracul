package de.visterion.dracul.strigoi.lazarus;

/** A screened quality-at-52w-low candidate — the wire shape returned by the tool webhook.
 *  {@code marketCap} is carried in MILLIONS of the reporting currency and only feeds the
 *  Altman-Z market-value-of-equity input during enrichment (it is not copied onto the
 *  enriched wire shape). {@code reportingCurrency} is the ISO-4217 code that market cap is
 *  quoted in (non-US path only; null for US) and is threaded into the non-US Altman-Z X4
 *  currency-consistency guard. */
public record LazarusCandidate(
        String symbol,
        String companyName,
        double currentPrice,
        double week52Low,
        double week52High,
        double pctAboveLow,
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
    public LazarusCandidate(
            String symbol, String companyName, double currentPrice, double week52Low,
            double week52High, double pctAboveLow, Double roaTtm, Double currentRatio,
            Double debtToEquity, Double grossMargin, Double netMargin, Double revenueGrowthYoy,
            Double epsGrowthYoy, Double priceToBook, Double peTtm, Double fcfPerShare,
            Double marketCap) {
        this(symbol, companyName, currentPrice, week52Low, week52High, pctAboveLow, roaTtm,
                currentRatio, debtToEquity, grossMargin, netMargin, revenueGrowthYoy, epsGrowthYoy,
                priceToBook, peTtm, fcfPerShare, marketCap, null);
    }
}
