package de.visterion.dracul.executor;

import java.util.Locale;

/**
 * How an open position is exited. {@link #STANDARD} is the executor's classic lifecycle:
 * chandelier trail, giveback, structured kill level, LLM soft exits and tranche 2.
 * {@link #CONVICTION} is the strigoi-tech basket profile (spec 2026-10-03 §5): emergency stop
 * below entry, half sold once a close reaches the target, the rest trailed below the highest
 * close, and a nightly catastrophe check. {@link #MOMENTUM} is the strigoi-momentum profile
 * (spec 2026-10-04 §3): the same wide emergency stop and fixed-notional entry, but no
 * target-half, no trail, no catastrophe flag — it leaves the book only at a monthly rebalance
 * ({@code HARD_REBALANCE}) or on the stop. Only code exits the two wide-stop profiles.
 *
 * <p>Consumers never test {@code == CONVICTION} for a shared behaviour; they ask the capability
 * ({@link #isWideStop}, {@link #hasTargetHalf}, {@link #hasTrail}, {@link #acceptsCatastropheFlag}).
 * Identity checks (the tech basket's own book) still compare to {@link #CONVICTION}.
 *
 * <p>The profile is DERIVED from the signal mechanism, never carried as a separate prey/signal
 * column: {@code TECH_CONVICTION} ⇒ CONVICTION, {@code MOMENTUM_12_1} ⇒ MOMENTUM, anything else
 * (and no mechanism) ⇒ STANDARD; trimmed and case-insensitive.
 */
public enum ExitProfile {
    STANDARD,
    CONVICTION,
    MOMENTUM;

    /** The one mechanism that maps to {@link #CONVICTION}. */
    public static final String TECH_CONVICTION = "TECH_CONVICTION";
    /** The one mechanism that maps to {@link #MOMENTUM}. */
    public static final String MOMENTUM_12_1 = "MOMENTUM_12_1";

    /** Profile stop / entry-band broker leg / post-fill widening, fixed-notional sizing,
     *  OrderGuard window bypass, no take-profit, SIZE_TOO_SMALL, no tranche 2, no soft trigger,
     *  no chandelier, no kill level, no giveback, {@code exit_position} → PROFILE_MANAGED,
     *  Gropar deferral, CORRELATED/CONCENTRATION/HEAT_LIMIT/PACE_LIMIT skipped. */
    public boolean isWideStop() {
        return this == CONVICTION || this == MOMENTUM;
    }

    /** A close at entry x (1 + target-pct) sells target-fraction (HARD_TARGET_HALF) — only when
     *  {@code dracul.executor.profiles.conviction.take-profit-enabled} is true; the trail arms only
     *  after a half-sale. */
    public boolean hasTargetHalf() {
        return this == CONVICTION;
    }

    /** After the half-sale the remainder trails highest close x (1 − trail-pct). The trail arms
     *  whenever {@code trim_count > 0} (a half-sale already happened), regardless of
     *  {@code dracul.executor.profiles.conviction.take-profit-enabled}: a position half-sold
     *  before the switch was turned off keeps its trail. With the switch off no NEW half-sale
     *  ever happens, so the trail never arms on a fresh position — but that is a consequence of
     *  {@link #hasTargetHalf()}'s gate, not a second check here. */
    public boolean hasTrail() {
        return this == CONVICTION;
    }

    /** strigoi-tech's nightly catastrophe flag may be set on the position. */
    public boolean acceptsCatastropheFlag() {
        return this == CONVICTION;
    }

    public static ExitProfile fromMechanism(String mechanism) {
        if (mechanism == null) return STANDARD;
        return switch (mechanism.trim().toUpperCase(Locale.ROOT)) {
            case TECH_CONVICTION -> CONVICTION;
            case MOMENTUM_12_1 -> MOMENTUM;
            default -> STANDARD;
        };
    }
}
