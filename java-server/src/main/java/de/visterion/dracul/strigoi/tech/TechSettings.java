package de.visterion.dracul.strigoi.tech;

import java.math.BigDecimal;

/** {@code dracul.strigoi.tech.*} (spec 2026-10-03 §4.1) plus the two connections it reads. */
public record TechSettings(int basketSize, int maxNewPerWeek, BigDecimal positionPct,
        BigDecimal minMarketCapUsdMillions, int reentryBlockDays, String executorConnection,
        String depotConnection, String instrumentCurrency) {
}
