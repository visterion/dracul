package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One earnings announcement with the as-announced surprise and revenue (nullable when unknown). */
public record EarningsObservation(
        String symbol,
        String companyName,
        LocalDate reportDate,
        BigDecimal epsActual,
        BigDecimal epsEstimate,
        BigDecimal epsSurprisePercent,
        BigDecimal revenueActual,
        BigDecimal revenueEstimate
) {}
