package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * The Tech-Sparplan knobs (spec 2026-10-06 §4, §7), bound once by {@link SavingsPlanConfig}.
 *
 * @param enabled gates only NEW adds; consolidation, the reconcile branch and the trigger
 *        exclusion stay active while any in-flight row exists (spec §7, R2 M8)
 * @param monthlyPct share of {@code dracul.executor.total-budget} saved per month
 * @param maxPositionPct per-position cap (market value) as a share of total-budget
 * @param basketCapPct cap of the whole CONVICTION basket (market value) as a share of total-budget
 * @param limitPremiumPct the add's limit sits this far above the close (marketable at a normal open)
 * @param catchUpWeekdays plan day = weekday 1; weekdays 2..catchUp catch up; catchUp+1 = MISSED
 * @param windowStartUtc both savings stages act only in [windowStartUtc, 24:00) UTC, Mon–Fri
 * @param tif the add's time in force: {@code gtc} (Agora default) or {@code day} — SIM probe V4(g)
 * @param placeFirst consolidation order: true = place the new stop, then cancel the old ones;
 *        false = cancel first, then place — SIM probe V3(b)
 */
public record SavingsPlanSettings(boolean enabled, BigDecimal monthlyPct, BigDecimal maxPositionPct,
        BigDecimal basketCapPct, BigDecimal limitPremiumPct, int catchUpWeekdays,
        LocalTime windowStartUtc, String tif, boolean placeFirst) {

    public SavingsPlanSettings {
        requireFraction("monthly-pct", monthlyPct);
        requireFraction("max-position-pct", maxPositionPct);
        requireFraction("basket-cap-pct", basketCapPct);
        requireFraction("limit-premium-pct", limitPremiumPct);
        if (catchUpWeekdays < 1 || catchUpWeekdays > 10) {
            throw new IllegalArgumentException(
                    "dracul.executor.savings-plan.catch-up-weekdays must be in [1, 10], got " + catchUpWeekdays);
        }
        if (windowStartUtc == null) {
            throw new IllegalArgumentException("dracul.executor.savings-plan.window-start-utc is required");
        }
        if (!"gtc".equals(tif) && !"day".equals(tif)) {
            throw new IllegalArgumentException(
                    "dracul.executor.savings-plan.tif must be gtc or day, got " + tif);
        }
    }

    /** The spec defaults: off, 0.02 / 0.08 / 0.50 / 0.02, catch-up 3, window 21:15 UTC, gtc, cancel-first. */
    public static SavingsPlanSettings defaults() {
        return new SavingsPlanSettings(false, new BigDecimal("0.02"), new BigDecimal("0.08"),
                new BigDecimal("0.50"), new BigDecimal("0.02"), 3, LocalTime.of(21, 15), "gtc", false);
    }

    private static void requireFraction(String key, BigDecimal v) {
        if (v == null || v.signum() <= 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("dracul.executor.savings-plan." + key
                    + " must be in (0, 1), got " + v);
        }
    }
}
