package de.visterion.dracul.strigoi.lazarus;

/** A screened quality-at-52w-low candidate — the wire shape returned by the tool webhook.
 *  {@code marketCap} is carried in Finnhub's native unit of USD MILLIONS and only feeds the
 *  Altman-Z market-value-of-equity input during enrichment (it is not copied onto the
 *  enriched wire shape). */
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
        Double marketCap
) {}
