package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * Decides, at place-entry time, what happens to a signal's structured kill level
 * ({@code kill_close_below}, spec 2026-10-02 §3.4). Pure and static, like {@link BrokerStop} and
 * {@link StopWindowRounding}: no I/O, no wiring.
 *
 * <p>BUY only. A null level, or a SELL, yields {@link Outcome#KEEP} with a null effective level —
 * there is nothing to enforce.
 *
 * <ul>
 *   <li><b>Breached</b> ({@code level >= basis}): on a {@link Mode#FRESH} placement the thesis
 *       would be dead on arrival, so the entry is {@link Outcome#REJECT}ed
 *       ({@code KILL_LEVEL_BREACHED}). On an {@link Mode#ADOPTED} order or fill a live broker
 *       order/holding already exists — rejecting would orphan it — so the trade is booked and the
 *       level is dropped ({@link #DROPPED_BREACHED_AT_ADOPTION}).</li>
 *   <li><b>Too tight</b> ({@code basis - level < 0.5 x atrEffective}): one ordinary day of noise
 *       would kill the thesis; the trade is kept, the level dropped ({@link #DROPPED_TOO_TIGHT}).
 *       Skipped when {@code atrEffective} is null or not positive.</li>
 *   <li>Otherwise {@link Outcome#KEEP} the level.</li>
 * </ul>
 *
 * <p>A null {@code basis} cannot be checked and keeps the level (defensive; every caller passes a
 * non-null order or fill price).
 */
public final class KillLevelGuard {

    /** Minimum distance between the entry basis and the level, in units of atr_effective. */
    public static final BigDecimal MIN_DISTANCE_ATR = new BigDecimal("0.5");

    /** {@code executor_position.kill_close_below_dropped} values. */
    public static final String DROPPED_TOO_TIGHT = "too_tight";
    public static final String DROPPED_BREACHED_AT_ADOPTION = "breached_at_adoption";

    /** FRESH = this call places a new bracket; ADOPTED = a working order or a fill that already
     *  exists at the broker is being booked (never rejectable). */
    public enum Mode { FRESH, ADOPTED }

    public enum Outcome { KEEP, DROP_TOO_TIGHT, DROP_BREACHED_AT_ADOPTION, REJECT }

    /**
     * @param outcome        what place-entry must do
     * @param requestedLevel the signal's raw level (null when it had none)
     * @param effectiveLevel the level to persist on the position — null unless KEEP (or REJECT,
     *                       where no position is written and the value is audit-only)
     * @param droppedReason  {@link #DROPPED_TOO_TIGHT}, {@link #DROPPED_BREACHED_AT_ADOPTION} or null
     * @param basis          the price the level was compared against
     */
    public record Result(Outcome outcome, BigDecimal requestedLevel, BigDecimal effectiveLevel,
                         String droppedReason, BigDecimal basis) {}

    private KillLevelGuard() {}

    public static Result evaluate(BigDecimal level, BigDecimal basis, BigDecimal atrEffective,
                                  String side, Mode mode) {
        if (level == null || !"BUY".equalsIgnoreCase(side)) {
            return new Result(Outcome.KEEP, level, null, null, basis);
        }
        if (basis == null) {
            return new Result(Outcome.KEEP, level, level, null, null);
        }
        if (level.compareTo(basis) >= 0) {
            return mode == Mode.FRESH
                    ? new Result(Outcome.REJECT, level, level, null, basis)
                    : new Result(Outcome.DROP_BREACHED_AT_ADOPTION, level, null,
                            DROPPED_BREACHED_AT_ADOPTION, basis);
        }
        if (atrEffective != null && atrEffective.signum() > 0
                && basis.subtract(level).compareTo(atrEffective.multiply(MIN_DISTANCE_ATR)) < 0) {
            return new Result(Outcome.DROP_TOO_TIGHT, level, null, DROPPED_TOO_TIGHT, basis);
        }
        return new Result(Outcome.KEEP, level, level, null, basis);
    }
}
