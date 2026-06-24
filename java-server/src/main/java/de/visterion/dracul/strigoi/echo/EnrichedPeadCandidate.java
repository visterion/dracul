package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Candidate enriched with deterministic SP1 PEAD signals. Nullable = unavailable. */
public record EnrichedPeadCandidate(
        String symbol,
        String companyName,
        LocalDate reportDate,
        long daysSinceReport,
        BigDecimal epsActual,
        BigDecimal epsEstimate,
        BigDecimal epsSurprisePercent,
        Double sue,
        Integer sueDecile,
        boolean sueApproximate,
        boolean sueAvailable,
        BigDecimal revenueSurprisePercent,
        boolean doubleBeat,
        Integer consecutiveBeats,
        BigDecimal currentPrice
) {}
