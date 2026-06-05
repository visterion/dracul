package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.finnhub.BasicFinancials;

/** One watchlist symbol plus its fetched financials (financials may be null). */
public record LazarusRaw(
        String symbol,
        String companyName,
        double currentPrice,
        BasicFinancials financials
) {}
