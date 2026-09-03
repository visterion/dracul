package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The buffer arithmetic for the price that actually rests at the broker, in one place.
 *
 * <p>A position has two stops. {@code active_stop} is the LOGICAL stop: the level the close-based
 * hard trigger tests and the chandelier ratchet raises. The broker's protective leg rests further
 * away — below it for a long, above it for a short — so an intraday wick cannot close a position
 * the close-based rule would have kept. The broker leg is a catastrophe backstop, never the
 * decision.
 *
 * <p>Four rules hold on every path, and each exists because breaking it silently changes what the
 * broker holds:
 * <ol>
 *   <li><b>Never crosses the logical stop.</b> BUY {@code min(logicalStop, raw)}, SELL
 *       {@code max(logicalStop, raw)} — applied again AFTER rounding, since rounding toward the
 *       entry can overshoot an unrounded logical stop by a fraction of a tick.</li>
 *   <li><b>Never non-positive.</b> A buffer wider than the stop itself yields a price no broker
 *       can hold; it is clamped to {@code max(raw, logicalStop x 0.5)} and reported as
 *       {@code clamped}.</li>
 *   <li><b>Proximity cap, entry and add-tranche only.</b> Saxo rejects a bracket whose legs sit
 *       outside a proximity band. The buffer shrinks first; if the LOGICAL stop alone already
 *       exceeds the band the broker stop equals it and today's behaviour applies — beyond the band
 *       Agora's far-stop fallback is the safety net, and tightening a stop we never chose would be
 *       worse than relying on it.</li>
 *   <li><b>Monotonic on the ratchet, and it outranks rule 1.</b> The broker leg never moves
 *       against the position, even when ATR expands faster than the high; it simply lags, and
 *       says so. The monotonic floor is applied LAST, after the "never crosses the logical stop"
 *       clamp, so a leg that already rests BEYOND the new chandelier stays where it is instead of
 *       being walked back down to it. Legacy rows reach that state: a partial ratchet confirms a
 *       new price on the leg it managed to move without advancing {@code active_stop}, so the leg
 *       can rest above the logical stop (BUY). Such a leg is then tighter than the logical stop
 *       until the chandelier catches up. That is accepted — moving a live protective leg against
 *       the position is the worse of the two — and {@code broker_stop_lags} records it.</li>
 * </ol>
 *
 * <p><b>{@code bufferAtr == 0} is the exact identity.</b> Both entry points then return their
 * input value verbatim — no rounding, no clamp, no cap — which is what makes the whole feature
 * mechanically provable as additive: the regression profile in the plan runs the entire suite with
 * the buffer at zero.
 *
 * <p><b>Rounding differs by path on purpose.</b> Entry and add-tranche use
 * {@link TickSize#roundStop} (toward the entry, as for every initial stop). The ratchet uses
 * FLOOR for BUY / CEILING for SELL — away from the entry, the same direction
 * {@code StopRatchetService.computeChandelier} already uses, so a trailing stop is never rounded
 * into a premature stop-out. {@code TickSize.roundStop} is deliberately NOT used on the ratchet.
 */
public final class BrokerStop {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private BrokerStop() { }

    /**
     * @param price the price to send to the broker
     * @param clamped the positivity clamp bound — the requested buffer was wider than the stop
     * @param capped the proximity cap bound (entry paths only)
     * @param lags the monotonic floor bound — the broker leg stayed where it was (ratchet only)
     */
    public record Result(BigDecimal price, boolean clamped, boolean capped, boolean lags) { }

    /**
     * The price the protective leg of a NEW bracket rests at (place-entry and add-tranche).
     *
     * @param side "BUY" or "SELL"
     * @param logicalStop the stop the book records as {@code active_stop}; never null
     * @param atrEff {@code max(atr22, atrShort)} for this symbol
     * @param bufferAtr how many ATRs to move the leg away from the logical stop; 0 = identity
     * @param entryPrice the (tick-rounded) entry price the proximity cap is measured against
     * @param maxDistancePct the proximity band as a fraction of {@code entryPrice} (e.g. 0.20)
     */
    public static Result forEntry(String side, BigDecimal logicalStop, BigDecimal atrEff,
            BigDecimal bufferAtr, BigDecimal entryPrice, BigDecimal maxDistancePct) {

        if (isIdentity(bufferAtr, atrEff)) {
            return new Result(logicalStop, false, false, false);
        }
        boolean buy = "BUY".equalsIgnoreCase(side);

        BigDecimal offset = bufferAtr.multiply(atrEff);
        BigDecimal raw = buy ? logicalStop.subtract(offset) : logicalStop.add(offset);
        raw = towardLogical(buy, logicalStop, raw);

        boolean clamped = false;
        if (raw.signum() <= 0) {
            raw = raw.max(logicalStop.multiply(HALF));
            clamped = true;
        }

        boolean capped = false;
        if (entryPrice != null && maxDistancePct != null) {
            BigDecimal band = entryPrice.multiply(maxDistancePct);
            BigDecimal bound = buy ? entryPrice.subtract(band) : entryPrice.add(band);
            boolean logicalAlreadyBeyond = buy
                    ? logicalStop.compareTo(bound) < 0
                    : logicalStop.compareTo(bound) > 0;
            if (logicalAlreadyBeyond) {
                // The logical stop itself is outside the band. Sending it unbuffered is exactly
                // today's behaviour; anything tighter would be a stop nobody chose.
                raw = logicalStop;
                capped = true;
            } else {
                boolean rawBeyond = buy ? raw.compareTo(bound) < 0 : raw.compareTo(bound) > 0;
                if (rawBeyond) {
                    raw = bound;
                    capped = true;
                }
            }
        }

        BigDecimal rounded = towardLogical(buy, logicalStop, TickSize.roundStop(side, raw));
        return new Result(rounded, clamped, capped, false);
    }

    /**
     * The price the EXISTING protective leg is moved to on a permitted ratchet.
     *
     * <p>There is no proximity cap here: the band is a statement about the distance from an entry
     * order, and a ratchet has none.
     *
     * @param chandelier the logical chandelier level, already rounded by
     *        {@code StopRatchetService.computeChandelier}
     * @param previousBrokerStop the price the leg rests at now. V48 backfills it for every OPEN
     *        row from the highest broker-confirmed stop price, so it is normally non-null; null
     *        means "no record of where the leg rests", and {@code activeStop} is then the floor.
     *        Treating null as zero would let the first post-V48 ratchet move a live leg DOWN by a
     *        whole buffer. The floor may sit ABOVE the chandelier (a legacy leg moved by a partial
     *        ratchet): it still wins, see rule 4 on the class.
     * @param activeStop the position's current logical stop, the fallback floor
     */
    public static Result forRatchet(String side, BigDecimal chandelier, BigDecimal atrEff,
            BigDecimal bufferAtr, BigDecimal previousBrokerStop, BigDecimal activeStop) {

        if (isIdentity(bufferAtr, atrEff)) {
            return new Result(chandelier, false, false, false);
        }
        boolean buy = "BUY".equalsIgnoreCase(side);

        BigDecimal offset = bufferAtr.multiply(atrEff);
        BigDecimal raw = buy ? chandelier.subtract(offset) : chandelier.add(offset);
        raw = towardLogical(buy, chandelier, raw);

        boolean clamped = false;
        if (raw.signum() <= 0) {
            raw = raw.max(chandelier.multiply(HALF));
            clamped = true;
        }

        // Away from the entry, matching computeChandelier -- NOT TickSize.roundStop.
        BigDecimal rounded = raw.setScale(2, buy ? RoundingMode.FLOOR : RoundingMode.CEILING);

        // LAST, deliberately after towardLogical: a leg resting beyond the chandelier is never
        // walked back to it (rule 4 outranks rule 1 on this path).
        BigDecimal floor = previousBrokerStop != null ? previousBrokerStop : activeStop;
        boolean lags = false;
        if (floor != null) {
            BigDecimal monotone = buy ? rounded.max(floor) : rounded.min(floor);
            lags = monotone.compareTo(rounded) != 0;
            rounded = monotone;
        }
        return new Result(rounded, clamped, false, lags);
    }

    /** Buffer zero -- or no usable ATR -- means "send the logical value verbatim". */
    private static boolean isIdentity(BigDecimal bufferAtr, BigDecimal atrEff) {
        return bufferAtr == null || bufferAtr.signum() == 0 || atrEff == null;
    }

    /** BUY: never above {@code logical}. SELL: never below it. */
    private static BigDecimal towardLogical(boolean buy, BigDecimal logical, BigDecimal value) {
        return buy ? value.min(logical) : value.max(logical);
    }
}
