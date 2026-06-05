package de.visterion.dracul.hunting.wikipedia;

import java.time.LocalDate;

/** A parsed row of the Wikipedia "List of S&P 500 companies" main constituents table. */
public record Sp500Constituent(
        String symbol,
        String companyName,
        LocalDate dateAdded
) {}
