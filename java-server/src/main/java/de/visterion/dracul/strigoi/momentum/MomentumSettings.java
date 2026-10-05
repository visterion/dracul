package de.visterion.dracul.strigoi.momentum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** {@code dracul.strigoi.momentum.*} (spec 2026-10-04 §5.1) plus the executor connection. */
public record MomentumSettings(int topN, int refillN, int catchUpWeekdays, int universeMin,
        BigDecimal completenessFloor, BigDecimal positionPct, int lookbackDays, int skipDays,
        BigDecimal gapSuspectPct, BigDecimal minPrice, int minEntryQty, Set<String> excludeSymbols,
        String executorConnection) {

    public static final String AGENT = "strigoi-momentum";
    /** The universe (Agora serves only sp500). */
    public static final String INDEX = "sp500";
    /** Symbols per get_indicators_batch call — ~5 calls for the S&P 500 (Lazarus precedent). */
    public static final int CHUNK_SIZE = 100;
    /** History asked from Agora; measured 2026-10-04 (spec §8 V1): roc231 carries 189 values. */
    public static final int FETCH_DAYS = 420;
    /** Trailing indicator values asked per series (Agora's MAX_SERIES). */
    public static final int SERIES = 250;
    /** Constant, rule-based confidence of a code-built prey (spec §5.3; ≥ min-confidence 0.40). */
    public static final double PREY_CONFIDENCE = 0.50;
    /** Horizon of a code-built prey in the {@code Horizons} grammar (spec §5.3, ruling MJ1); the
     *  rebalance wording lives in the thesis / kill criteria. */
    public static final String HORIZON = "1m";
    /** A daily return at or below −15 % (but above the suspect gap) is reported as
     *  {@code possible_corporate_action} — information for the LLM only (spec §5.2). */
    public static final BigDecimal MEDIUM_GAP_PCT = new BigDecimal("0.15");

    public MomentumSettings {
        if (topN < 1) throw new IllegalArgumentException("dracul.strigoi.momentum.top-n must be >= 1, got " + topN);
        if (refillN < 0) throw new IllegalArgumentException("dracul.strigoi.momentum.refill-n must be >= 0, got " + refillN);
        if (catchUpWeekdays < 0 || catchUpWeekdays > 10) {
            throw new IllegalArgumentException("dracul.strigoi.momentum.catch-up-weekdays must be in 0..10, got " + catchUpWeekdays);
        }
        if (universeMin < 1) throw new IllegalArgumentException("dracul.strigoi.momentum.universe-min must be >= 1, got " + universeMin);
        if (completenessFloor == null || completenessFloor.signum() <= 0 || completenessFloor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("dracul.strigoi.momentum.completeness-floor must be in (0, 1], got " + completenessFloor);
        }
        requireFraction("position-pct", positionPct);
        requireFraction("gap-suspect-pct", gapSuspectPct);
        if (skipDays < 1 || lookbackDays <= skipDays || skipDays + 1 > SERIES) {
            throw new IllegalArgumentException("dracul.strigoi.momentum: need 1 <= skip-days < lookback-days and skip-days < "
                    + SERIES + ", got lookback " + lookbackDays + " / skip " + skipDays);
        }
        if (minPrice == null || minPrice.signum() < 0) throw new IllegalArgumentException("dracul.strigoi.momentum.min-price must be >= 0, got " + minPrice);
        if (minEntryQty < 1) throw new IllegalArgumentException("dracul.strigoi.momentum.min-entry-qty must be >= 1, got " + minEntryQty);
        excludeSymbols = excludeSymbols == null ? Set.of() : excludeSymbols.stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** ROC period whose value at t−skip is the 12-1 momentum close(t−skip)/close(t−lookback) − 1 (231). */
    public int rocPeriod() {
        return lookbackDays - skipDays;
    }

    /** One-day return at or above which a bar is an unadjusted reverse split: 1/(1 − gap) − 1,
     *  truncated to three decimals (0.35 → 0.538, spec §5.2). */
    public BigDecimal upGapPct() {
        return BigDecimal.ONE.divide(BigDecimal.ONE.subtract(gapSuspectPct), 3, RoundingMode.DOWN)
                .subtract(BigDecimal.ONE);
    }

    /** "GOOG, fox ,NWS" → {GOOG, FOX, NWS}. */
    public static Set<String> parseSymbols(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private static void requireFraction(String key, BigDecimal v) {
        if (v == null || v.signum() <= 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("dracul.strigoi.momentum." + key + " must be in (0, 1), got " + v);
        }
    }
}
