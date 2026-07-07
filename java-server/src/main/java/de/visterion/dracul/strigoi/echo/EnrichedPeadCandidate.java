package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Candidate enriched with deterministic PEAD signals (SP1 surprise/quality + SP2
 *  market reaction). Nullable / {@code *Available=false} = signal unavailable. */
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
        BigDecimal currentPrice,
        // SP2 market reaction
        BigDecimal announcementCar1d,
        BigDecimal announcementCar3d,
        boolean carAvailable,
        BigDecimal abnormalVolume,
        BigDecimal momentum6_12m,
        BigDecimal adv,
        Double marketCap,
        Double beta,
        String sector,
        boolean metricsAvailable,
        // SP3 earnings-quality / timing (soft signals; hard-skips already applied server-side)
        BigDecimal accrualRatio,
        boolean accrualsAvailable,
        Integer netEstimateRevisionsProxy,
        String netEstimateRevisionsDirection,
        boolean revisionsAvailable,
        LocalDate nextEarningsDate,
        Integer daysToNextEarnings
) {}
