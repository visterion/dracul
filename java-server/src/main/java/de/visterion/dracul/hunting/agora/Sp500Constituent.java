package de.visterion.dracul.hunting.agora;

import java.time.LocalDate;

/** A row of the S&P 500 index constituents list, fetched via Agora. */
public record Sp500Constituent(
        String symbol,
        String companyName,
        LocalDate dateAdded
) {}
