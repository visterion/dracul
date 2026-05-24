package de.visterion.dracul.hunting.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Form4Filing(
        String ticker,
        String filerName,
        String filerRole,
        LocalDate transactionDate,
        BigDecimal sharesAcquired,
        BigDecimal dollarValue,
        String transactionCode   // "P" = Purchase, "S" = Sale
) {}
