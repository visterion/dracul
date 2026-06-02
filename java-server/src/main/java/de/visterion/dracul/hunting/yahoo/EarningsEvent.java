package de.visterion.dracul.hunting.yahoo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EarningsEvent(
        String symbol,
        String companyName,
        LocalDate reportDate,
        BigDecimal epsActual,
        BigDecimal epsEstimate,
        BigDecimal surprisePercent
) {}
