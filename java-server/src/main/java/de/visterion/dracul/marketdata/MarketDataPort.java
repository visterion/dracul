package de.visterion.dracul.marketdata;

public interface MarketDataPort {
    MarketData resolve(String symbol);
}
