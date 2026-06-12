package de.visterion.dracul.marketdata;

import java.math.BigDecimal;

/** Lightweight price snapshot for the on-read watchlist refresh (no history). */
public record Quote(BigDecimal price, BigDecimal dayChangePercent) {}
