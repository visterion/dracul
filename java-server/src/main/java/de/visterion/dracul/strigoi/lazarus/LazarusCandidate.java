package de.visterion.dracul.strigoi.lazarus;

/** A screened quality-at-52w-low candidate — the wire shape returned by the tool webhook.
 *  {@code marketCap} is carried in MILLIONS of the reporting currency and only feeds the
 *  Altman-Z market-value-of-equity input during enrichment (it is not copied onto the
 *  enriched wire shape). {@code reportingCurrency} is the ISO-4217 code that market cap is
 *  quoted in (non-US path only; null for US) and is threaded into the non-US Altman-Z X4
 *  currency-consistency guard. {@code cheapGatePassed} records whether the candidate cleared
 *  the P/B or P/FCF cheapness gate on its own; {@code listingResolution} records whether the
 *  listing behind {@code marketCap}/{@code reportingCurrency} has been resolved yet — the
 *  screener itself can never resolve it (pure, I/O-free) and always sets
 *  {@link ListingResolution#UNKNOWN}; a later stage replaces the value once it has called out
 *  to resolve the listing. */
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
        String reportingCurrency,
        boolean cheapGatePassed,
        ListingResolution listingResolution
) {
    /** Back-compat convenience for US callers/tests with no reporting currency and no
     *  gate/listing state (defaults: reportingCurrency null, cheapGatePassed false,
     *  listingResolution UNKNOWN). {@code cheapGatePassed} defaults to {@code false}, not
     *  {@code true}: {@code true} means "cleared the cheapness gate on its own" and, from
     *  Task 4 onward, skips the size/listing/FX re-check entirely — a fail-open default here
     *  would silently disable that guard for every caller that doesn't set the field
     *  explicitly. Fail-closed instead. */
    public LazarusCandidate(
            String symbol, String companyName, double currentPrice, double week52Low,
            double week52High, double pctAboveLow, Double roaTtm, Double currentRatio,
            Double debtToEquity, Double grossMargin, Double netMargin, Double revenueGrowthYoy,
            Double epsGrowthYoy, Double priceToBook, Double peTtm, Double fcfPerShare,
            Double marketCap) {
        this(symbol, companyName, currentPrice, week52Low, week52High, pctAboveLow, roaTtm,
                currentRatio, debtToEquity, grossMargin, netMargin, revenueGrowthYoy, epsGrowthYoy,
                priceToBook, peTtm, fcfPerShare, marketCap, null, false, ListingResolution.UNKNOWN);
    }

    /** Back-compat convenience for callers that set reportingCurrency but not the gate/listing
     *  state (defaults: cheapGatePassed false, listingResolution UNKNOWN — see the other
     *  back-compat constructor's Javadoc for why {@code false}, not {@code true}, is the safe
     *  default). */
    public LazarusCandidate(
            String symbol, String companyName, double currentPrice, double week52Low,
            double week52High, double pctAboveLow, Double roaTtm, Double currentRatio,
            Double debtToEquity, Double grossMargin, Double netMargin, Double revenueGrowthYoy,
            Double epsGrowthYoy, Double priceToBook, Double peTtm, Double fcfPerShare,
            Double marketCap, String reportingCurrency) {
        this(symbol, companyName, currentPrice, week52Low, week52High, pctAboveLow, roaTtm,
                currentRatio, debtToEquity, grossMargin, netMargin, revenueGrowthYoy, epsGrowthYoy,
                priceToBook, peTtm, fcfPerShare, marketCap, reportingCurrency, false,
                ListingResolution.UNKNOWN);
    }
}
