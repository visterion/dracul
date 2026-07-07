package de.visterion.dracul.strigoi.insider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InsiderCluster(
        String ticker,
        String companyName,
        List<InsiderFiler> filers,
        LocalDate windowStart,
        LocalDate windowEnd,
        BigDecimal totalDollarValue,
        BigDecimal totalShares
) {}
