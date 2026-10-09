package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Parameters of the wide-stop exit profiles CONVICTION and MOMENTUM (shared stop arithmetic under
 * dracul.executor.profiles.conviction.*; rename to a wide-stop key is out of scope, spec 2026-10-04
 * §3; CONVICTION spec 2026-10-03 §5.2) and the price arithmetic every consumer shares, so
 * place-entry, the hard trigger and the stop ratchet can never disagree about a level.
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
 * @param momentumPositionPct exit profile MOMENTUM's size per name, bound from
 *        {@code dracul.strigoi.momentum.position-pct} (one key, read by executor and hunter)
 * @param momentumMinEntryQty MOMENTUM's fewest shares, bound from
 *        {@code dracul.strigoi.momentum.min-entry-qty}
 * @param takeProfitEnabled bound from {@code dracul.executor.profiles.conviction.take-profit-enabled};
 *        when false (default) the half-sale never fires, so the trail, which arms only after a
 *        half-sale, never arms either: a CONVICTION position exits only on the emergency stop or
 *        a catastrophe flag; spec 2026-10-09
 */
public record ConvictionProfile(BigDecimal emergencyStopPct, BigDecimal targetPct,
        BigDecimal targetFraction, BigDecimal trailPct, int minEntryQty,
        BigDecimal entryBrokerStopPct, BigDecimal positionPct,
        BigDecimal momentumPositionPct, int momentumMinEntryQty, boolean takeProfitEnabled) {

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
        if (momentumPositionPct == null || momentumPositionPct.signum() <= 0
                || momentumPositionPct.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(
                    "dracul.strigoi.momentum.position-pct must be in (0, 1), got " + momentumPositionPct);
        }
        if (momentumMinEntryQty < 1) {
            throw new IllegalArgumentException(
                    "dracul.strigoi.momentum.min-entry-qty must be >= 1, got " + momentumMinEntryQty);
        }
    }

    /** The spec defaults: 0.35 / 0.30 / 0.5 / 0.30 / 2 / 0.20 / 0.03, MOMENTUM 0.025 / 1,
     *  take-profit disabled. */
    public static ConvictionProfile defaults() {
        return new ConvictionProfile(new BigDecimal("0.35"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 2, new BigDecimal("0.20"),
                new BigDecimal("0.03"), new BigDecimal("0.025"), 1, false);
    }

    /** A copy with the take-profit switch set (tests and config binding). */
    public ConvictionProfile withTakeProfitEnabled(boolean enabled) {
        return new ConvictionProfile(emergencyStopPct, targetPct, targetFraction, trailPct, minEntryQty,
                entryBrokerStopPct, positionPct, momentumPositionPct, momentumMinEntryQty, enabled);
    }

    /** Fixed size per name as a fraction of total-budget (spec 2026-10-04 §3): MOMENTUM reads
     *  {@code dracul.strigoi.momentum.position-pct}, every other profile the CONVICTION
     *  {@code dracul.strigoi.tech.position-pct} (STANDARD never reads it). */
    public BigDecimal pctFor(ExitProfile profile) {
        return profile == ExitProfile.MOMENTUM ? momentumPositionPct : positionPct;
    }

    /** Fewest shares a wide-stop entry may buy: CONVICTION 2 (the half-sale needs them),
     *  MOMENTUM 1 (no half-sale; with 2 a high-priced name would be SIZE_TOO_SMALL). */
    public int minEntryQtyFor(ExitProfile profile) {
        return profile == ExitProfile.MOMENTUM ? momentumMinEntryQty : minEntryQty;
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
