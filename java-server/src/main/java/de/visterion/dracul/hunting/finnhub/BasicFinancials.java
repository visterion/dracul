package de.visterion.dracul.hunting.finnhub;

/**
 * Subset of Finnhub's basic-financials ("metric") object. All fields are boxed
 * Double so an absent metric (null) is distinguishable from a real 0. Percent
 * metrics (ROA, margins, growth) are in Finnhub's native percent units.
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
        Double fcfPerShare
) {}
