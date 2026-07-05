package de.visterion.dracul.strigoi.lazarus;


/** One watchlist symbol plus its fetched financials (financials may be null). */
public record LazarusRaw(
        String symbol,
        String companyName,
        double currentPrice,
        BasicFinancials financials
) {}
