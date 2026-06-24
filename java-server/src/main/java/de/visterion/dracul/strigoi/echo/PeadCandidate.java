package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeadCandidate(
        String symbol,
        String companyName,
        LocalDate reportDate,
        BigDecimal epsActual,
        BigDecimal epsEstimate,
        BigDecimal surprisePercent,
        BigDecimal revenueActual,
        BigDecimal revenueEstimate,
        BigDecimal currentPrice
) {}
