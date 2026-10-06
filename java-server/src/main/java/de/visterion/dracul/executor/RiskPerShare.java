package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * The R denominator — risk per share — in ONE place (spec 2026-10-06 §5.2, R2 M10, R3 M3).
 *
 * <p>Exit profile CONVICTION: {@code entry_price × emergency-stop-pct}, the risk of the CURRENT
 * average, always positive. Savings adds move the average while {@code initial_stop} stays immutable,
 * so after a few falling adds {@code entry − initial_stop} reaches zero or flips sign and would turn a
 * loss into a positive R in {@code realized_r}, {@code r_value}, {@code mfe_r} and the calibration.
 * Without an add this is the old value, because {@code initial_stop = entry × (1 − 0.35)}.
 *
 * <p>Every other profile keeps the side-aware {@code entry − initial_stop} (BUY) /
 * {@code initial_stop − entry} (SELL) exactly as every call site computed it before — including a
 * zero or negative result, which each caller guards as it always did.
 */
public final class RiskPerShare {

    private RiskPerShare() {
    }

    /** @return the risk per share, or null when an input is missing */
    public static BigDecimal of(ExecutorPosition p, ConvictionProfile profile) {
        if (p.profile() == ExitProfile.CONVICTION) {
            return p.entryPrice() == null ? null : p.entryPrice().multiply(profile.emergencyStopPct());
        }
        if (p.entryPrice() == null || p.initialStop() == null) return null;
        return "SELL".equals(p.side())
                ? p.initialStop().subtract(p.entryPrice())
                : p.entryPrice().subtract(p.initialStop());
    }
}
