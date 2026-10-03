package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Parameters of exit profile {@link ExitProfile#CONVICTION} (spec 2026-10-03 §5.2) and the price
 * arithmetic every consumer shares, so place-entry, the hard trigger and the stop ratchet can
 * never disagree about a level.
 *
 * @param emergencyStopPct logical stop distance below (BUY) the entry
 * @param targetPct a close at or above entry x (1 + targetPct) sells {@code targetFraction}
 * @param targetFraction fraction sold at the target (the "half")
 * @param trailPct after the half-sale the remainder trails at highest close x (1 − trailPct)
 * @param minEntryQty fewer shares can never be half-sold (the broker floors the fraction)
 * @param entryBrokerStopPct the broker's proximity band at entry: the protective leg starts here
 *        until it is widened to the logical stop (measured on SIM 2026-10-03: −20 % accepted,
 *        −25 % rejected)
 * @param positionPct fixed size per basket name as a fraction of {@code dracul.executor.total-budget};
 *        bound from {@code dracul.strigoi.tech.position-pct} — ONE key for executor and hunter
 */
public record ConvictionProfile(BigDecimal emergencyStopPct, BigDecimal targetPct,
        BigDecimal targetFraction, BigDecimal trailPct, int minEntryQty,
        BigDecimal entryBrokerStopPct, BigDecimal positionPct) {

    public ConvictionProfile {
        requireFraction("emergency-stop-pct", emergencyStopPct);
        if (targetPct == null || targetPct.signum() <= 0) {
            throw new IllegalArgumentException(
                    "dracul.executor.profiles.conviction.target-pct must be > 0, got " + targetPct);
        }
        requireFraction("target-fraction", targetFraction);
        requireFraction("trail-pct", trailPct);
        requireFraction("entry-broker-stop-pct", entryBrokerStopPct);
        requireFraction("position-pct", positionPct);
        if (minEntryQty < 1) {
            throw new IllegalArgumentException(
                    "dracul.executor.profiles.conviction.min-entry-qty must be >= 1, got " + minEntryQty);
        }
    }

    /** The spec defaults: 0.35 / 0.30 / 0.5 / 0.30 / 2 / 0.20 / 0.033. */
    public static ConvictionProfile defaults() {
        return new ConvictionProfile(new BigDecimal("0.35"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 2, new BigDecimal("0.20"),
                new BigDecimal("0.033"));
    }

    /** Logical emergency stop, tick-rounded toward the entry like every initial stop. */
    public BigDecimal emergencyStop(String side, BigDecimal entry) {
        BigDecimal raw = isBuy(side)
                ? entry.multiply(BigDecimal.ONE.subtract(emergencyStopPct))
                : entry.multiply(BigDecimal.ONE.add(emergencyStopPct));
        return TickSize.roundStop(side, raw);
    }

    /** Where the bracket's protective leg rests at entry: the broker's band, but never BEYOND the
     *  logical stop (a band wider than the emergency stop puts the leg on the logical stop). */
    public BigDecimal entryBrokerStop(String side, BigDecimal entry, BigDecimal logicalStop) {
        BigDecimal raw = isBuy(side)
                ? entry.multiply(BigDecimal.ONE.subtract(entryBrokerStopPct))
                : entry.multiply(BigDecimal.ONE.add(entryBrokerStopPct));
        BigDecimal rounded = TickSize.roundStop(side, raw);
        return isBuy(side) ? rounded.max(logicalStop) : rounded.min(logicalStop);
    }

    /** The close at or above which the target-half fires (BUY; the basket is long-only). */
    public BigDecimal targetPrice(BigDecimal entry) {
        return entry.multiply(BigDecimal.ONE.add(targetPct));
    }

    /** Trail level after the half-sale, rounded AWAY from the market like the chandelier. */
    public BigDecimal trailStop(String side, BigDecimal highest) {
        return isBuy(side)
                ? highest.multiply(BigDecimal.ONE.subtract(trailPct)).setScale(2, RoundingMode.FLOOR)
                : highest.multiply(BigDecimal.ONE.add(trailPct)).setScale(2, RoundingMode.CEILING);
    }

    private static boolean isBuy(String side) {
        return !"SELL".equalsIgnoreCase(side);
    }

    private static void requireFraction(String key, BigDecimal v) {
        if (v == null || v.signum() <= 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("dracul.executor.profiles.conviction." + key
                    + " must be in (0, 1), got " + v);
        }
    }
}
