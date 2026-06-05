package de.visterion.dracul.strigoi.lazarus;

/** A screened quality-at-52w-low candidate — the wire shape returned by the tool webhook. */
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
        Double fcfPerShare
) {}
