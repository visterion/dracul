package de.visterion.dracul.marketdata;

/** One instrument search result as served to Chronicle. No currency — the search has none. */
public record InstrumentSearchHit(String symbol, String name, String exchange, String type) {}
