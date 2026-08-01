package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Encapsulates the entry-path stop-rounding sequence: derive the protective-stop window from
 * the tick-rounded order price, round the window bounds inward, and round the LLM's proposed
 * stop onto the tick grid within that window — unless the rounded window is degenerate
 * (inverted), in which case stop rounding is omitted entirely and the raw proposal is clamped
 * into the RAW (unrounded) window, exactly as before tick rounding existed.
 *
 * <p>Pure, no Spring, no state. Encapsulates three rules, each a defect found in review of the
 * naive version of this sequence:
 *
 * <ol>
 *   <li><b>Inward bound rounding is asymmetric by ROLE, not by side.</b> {@code stopMin} (the
 *       lower bound) always rounds {@link RoundingMode#CEILING}; {@code stopMax} (the upper
 *       bound) always rounds {@link RoundingMode#FLOOR} — for BUY and SELL alike. Both
 *       directions move toward the window's interior, guaranteeing the rounded window is a
 *       subset of the raw one. {@link TickSize#roundStop} is deliberately NOT used for the
 *       bounds: it knows only one direction per side (toward the entry), so applying it to both
 *       bounds necessarily widens one of them.</li>
 *   <li><b>The window is derived from the SAME rounded price that {@code PositionSizer.size}
 *       is called with</b> — never from the raw order price. The window depends on the entry
 *       price (e.g. BUY: {@code stopMax = price - 2.5*ATR}), so mixing a raw-price window with
 *       a rounded-price sizing call yields two different windows and can reject a stop that is
 *       actually valid.</li>
 *   <li><b>Degeneracy is checked BEFORE the clamp.</b> If the rounded window is inverted
 *       ({@code roundedMin > roundedMax} — any raw window narrower than one tick produces this),
 *       there is no valid clamp target inside it, so stop rounding is skipped entirely and the
 *       raw proposal is clamped into the raw (unrounded) window, matching pre-tick-rounding
 *       behavior.</li>
 * </ol>
 */
public final class StopWindowRounding {

    private StopWindowRounding() { }

    /**
     * @param stop the stop price to send to the broker
     * @param stopMin the window lower bound actually used to arrive at {@code stop} — pass
     *        straight through to {@link OrderGuard#check}; {@code null} when the sizer's window
     *        has no bounds to check against
     * @param stopMax the window upper bound actually used to arrive at {@code stop}; {@code null}
     *        under the same condition as {@code stopMin}
     */
    public record Result(BigDecimal stop, BigDecimal stopMin, BigDecimal stopMax) { }

    /**
     * @param side "BUY" or "SELL"
     * @param orderPriceRounded the tick-rounded order price; MUST be the same price passed to
     *        {@code PositionSizer.size} for this order (rule 2 above)
     * @param atr average true range (instrument currency)
     * @param swingLow recent swing low, nullable (instrument currency)
     * @param rawProposedStop the LLM's raw proposed stop, not yet rounded or clamped
     * @param sizer used to derive the window from {@code orderPriceRounded}
     * @return the stop to send plus the bounds that were actually used to arrive at it
     */
    public static Result compute(String side, BigDecimal orderPriceRounded, BigDecimal atr,
            BigDecimal swingLow, BigDecimal rawProposedStop, PositionSizer sizer) {

        StopWindow window = sizer.stopWindow(side, orderPriceRounded, atr, swingLow);

        if (window.stopMin() == null || window.stopMax() == null) {
            // No window to check degeneracy or clamp against; round the proposal on its own.
            return new Result(TickSize.roundStop(side, rawProposedStop), null, null);
        }

        BigDecimal roundedMin = window.stopMin().setScale(2, RoundingMode.CEILING);
        BigDecimal roundedMax = window.stopMax().setScale(2, RoundingMode.FLOOR);

        if (roundedMin.compareTo(roundedMax) > 0) {
            // Degenerate: rounding inward inverted the window, so there is no valid rounded
            // clamp target. Stop rounding is omitted entirely; fall back to the raw clamp
            // against the raw window, exactly as before tick rounding existed.
            BigDecimal fallback = clamp(rawProposedStop, window.stopMin(), window.stopMax());
            return new Result(fallback, window.stopMin(), window.stopMax());
        }

        BigDecimal roundedStop = TickSize.roundStop(side, rawProposedStop);
        BigDecimal clamped = clamp(roundedStop, roundedMin, roundedMax);
        return new Result(clamped, roundedMin, roundedMax);
    }

    private static BigDecimal clamp(BigDecimal proposed, BigDecimal min, BigDecimal max) {
        if (proposed == null || proposed.compareTo(min) < 0) return min;
        if (proposed.compareTo(max) > 0) return max;
        return proposed;
    }
}
