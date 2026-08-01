package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the stop-window tick-rounding invariants that the entry-path rework (next task) must
 * produce. {@link PositionSizer} and {@link OrderGuard} are unmodified by this task — these
 * tests assemble the future entry-path pipeline (round entry / round window bounds via
 * {@link TickSize}, clamp, then call the existing pure {@code sizer.size}/{@code orderGuard.check})
 * the same way {@code ExecutorWebhookController} does today at lines 540-550 and 613-614, except
 * with {@link TickSize} rounding inserted. Several assertions are RED on purpose: they fail
 * against the naive (rounding, then reusing the existing unmodified clamp/size/guard calls
 * as-is) pipeline, which is exactly the gap the next task has to close.
 *
 * <p>Deliberately not testing "no rounding at all" (that already passes trivially, since neither
 * PositionSizer nor OrderGuard perform any rounding) — that would prove nothing.
 */
class StopWindowRoundingTest {

    private final PositionSizer sizer = new PositionSizer();
    private final OrderGuard orderGuard = new OrderGuard();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** Mirrors the clamp in ExecutorWebhookController.java:542-547. */
    private static BigDecimal clamp(BigDecimal proposed, BigDecimal min, BigDecimal max) {
        BigDecimal clamped = proposed;
        if (clamped == null || clamped.compareTo(min) < 0) {
            clamped = min;
        } else if (clamped.compareTo(max) > 0) {
            clamped = max;
        }
        return clamped;
    }

    /**
     * Inward window-bound rounding, per the (now normative) spec table: the lower bound always
     * rounds CEILING, the upper bound always rounds FLOOR — regardless of side. Both directions
     * move toward the window's interior, which guarantees {@code [rounded] subset of [raw]}.
     * {@link TickSize#roundStop} is deliberately NOT used here: it only knows one direction per
     * side (toward the entry), so applying it to both bounds necessarily widens one of them.
     */
    private static BigDecimal roundBoundMinInward(BigDecimal stopMin) {
        return stopMin.setScale(2, RoundingMode.CEILING);
    }

    private static BigDecimal roundBoundMaxInward(BigDecimal stopMax) {
        return stopMax.setScale(2, RoundingMode.FLOOR);
    }

    // ---- P1: the degenerate window. Empirically confirmed today's (unrounded) clamped stop by
    // running sizer.stopWindow + the controller's clamp verbatim against BUY 1.50 / ATR 0.03 /
    // swingLow 1.399: window = [1.3915, 1.399] (width 0.0075 < one tick), clamping any
    // out-of-window proposal lands on 1.3915 (stopMin) or 1.399 (stopMax), and OrderGuard passes
    // (ok=true, reason=null). RejectReason.SUB_TICK_STOP_WINDOW does not exist and must not be
    // referenced — it was cut from the design.

    private static final BigDecimal RAW_CLAMPED_STOP_TODAY_BUY = bd("1.3915");
    private static final BigDecimal RAW_CLAMPED_STOP_TODAY_SELL = bd("1.6085");

    @Test
    void degenerateWindowFallsBackToTheRawClampedStop() {
        BigDecimal price = bd("1.50");
        BigDecimal atr = bd("0.03");
        BigDecimal swingLow = bd("1.399");

        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, swingLow);
        assertThat(rawWindow.stopMax().subtract(rawWindow.stopMin()))
                .isLessThan(TickSize.tickFor(price)); // window narrower than one tick

        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());
        // Correct inward rounding of a sub-tick window inverts it: no valid clamp target exists.
        // This is the real degeneracy, independent of which rounding tool is used for the bounds
        // — it is why the degeneracy check must run BEFORE the clamp.
        assertThat(roundedMin).isGreaterThan(roundedMax);

        // Proposal far outside the window on the low side, exactly as an LLM proposal that
        // ignores the risk layer would look.
        BigDecimal clamped = clamp(bd("1.00"), roundedMin, roundedMax);

        Sizing sizing = sizer.size("BUY", roundedEntry, atr, swingLow, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), roundedEntry, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        assertThat(guard.reason()).isNull(); // must not be NO_STOP from a rounding artifact
        assertThat(clamped).usingComparator(BigDecimal::compareTo).isEqualTo(RAW_CLAMPED_STOP_TODAY_BUY);
    }

    @Test
    void degenerateWindowFallsBackToTheRawClampedStop_sell() {
        BigDecimal price = bd("1.50");
        BigDecimal atr = bd("0.03");
        BigDecimal swingHigh = bd("1.601");

        StopWindow rawWindow = sizer.stopWindow("SELL", price, atr, swingHigh);
        assertThat(rawWindow.stopMax().subtract(rawWindow.stopMin()))
                .isLessThan(TickSize.tickFor(price));

        BigDecimal roundedEntry = TickSize.roundEntry("SELL", price);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());
        assertThat(roundedMin).isGreaterThan(roundedMax); // inverted, same as the BUY mirror

        BigDecimal clamped = clamp(bd("2.00"), roundedMin, roundedMax);

        Sizing sizing = sizer.size("SELL", roundedEntry, atr, swingHigh, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("SELL", sizing.qty(), roundedEntry, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        assertThat(guard.reason()).isNull();
        assertThat(clamped).usingComparator(BigDecimal::compareTo).isEqualTo(RAW_CLAMPED_STOP_TODAY_SELL);
    }

    // ---- P1: risk-per-share bound. The old claim "only SELL is affected" was wrong: for BUY,
    // PositionSizer.java:103 sets stopMax = anchor (the bound nearest the entry), so rounding
    // that bound inward moves the tightest permitted stop AWAY from the entry and
    // rPerShare = price.subtract(stopPrice) grows. Fixture: price 100.017 / ATR 3.33 (chosen so
    // neither the anchor nor the entry lands on a tick), proposal far tighter than the anchor so
    // the clamp binds at the tight bound on both sides.

    @Test
    void riskPerShareChangesByAtMostOneTick_buyBindingAtStopMax() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal proposed = bd("99"); // tighter than the anchor -> clamp binds at stopMax

        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);
        BigDecimal clampedRaw = clamp(proposed, rawWindow.stopMin(), rawWindow.stopMax());
        Sizing rawSizing = sizer.size("BUY", price, atr, null, clampedRaw, bd("1000"), BigDecimal.ONE);

        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());
        BigDecimal clampedRounded = clamp(proposed, roundedMin, roundedMax);
        Sizing roundedSizing = sizer.size("BUY", roundedEntry, atr, null, clampedRounded, bd("1000"), BigDecimal.ONE);

        BigDecimal delta = roundedSizing.rPerShare().subtract(rawSizing.rPerShare()).abs();
        assertThat(delta).isLessThanOrEqualTo(TickSize.tickFor(price));
    }

    @Test
    void riskPerShareChangesByAtMostOneTick_sellBindingAtStopMin() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal proposed = bd("101"); // tighter than the anchor -> clamp binds at stopMin

        StopWindow rawWindow = sizer.stopWindow("SELL", price, atr, null);
        BigDecimal clampedRaw = clamp(proposed, rawWindow.stopMin(), rawWindow.stopMax());
        Sizing rawSizing = sizer.size("SELL", price, atr, null, clampedRaw, bd("1000"), BigDecimal.ONE);

        BigDecimal roundedEntry = TickSize.roundEntry("SELL", price);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());
        BigDecimal clampedRounded = clamp(proposed, roundedMin, roundedMax);
        Sizing roundedSizing = sizer.size("SELL", roundedEntry, atr, null, clampedRounded, bd("1000"), BigDecimal.ONE);

        BigDecimal delta = roundedSizing.rPerShare().subtract(rawSizing.rPerShare()).abs();
        assertThat(delta).isLessThanOrEqualTo(TickSize.tickFor(price));
    }

    // ---- The degeneration fallback rests on this identity. If it ever breaks, this test must
    // fail before anyone touches the fallback logic that depends on it.

    @Test
    void sizingStopMinEqualsStopWindowStopMin() {
        BigDecimal price = bd("100");
        BigDecimal atr = bd("2");
        BigDecimal swingLow = bd("95");

        Sizing sizing = sizer.size("BUY", price, atr, swingLow, bd("96"), bd("1000"), BigDecimal.ONE);
        StopWindow window = sizer.stopWindow("BUY", price, atr, swingLow);

        assertThat(sizing.stopMin()).usingComparator(BigDecimal::compareTo).isEqualTo(window.stopMin());
    }

    // ---- Rounding must never widen the window. Strengthened beyond a tautology about
    // RoundingMode semantics: each test also computes the bounds with the plausible WRONG tool
    // (TickSize.roundStop, my original bug from the first fix round) and asserts THAT violates
    // the subset property. That makes the property nontrivial — there is a real, easy-to-reach
    // wrong implementation this test would catch — rather than merely re-deriving what CEILING/
    // FLOOR already guarantee by definition.

    @Test
    void roundedWindowIsSubsetOfRawWindow_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");

        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());

        assertThat(roundedMin).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(roundedMax).isLessThanOrEqualTo(rawWindow.stopMax());

        // The plausible wrong implementation (TickSize.roundStop applied to both bounds, my
        // original bug) widens stopMax for BUY because it always rounds toward the entry
        // (CEILING for BUY), not toward the window's interior.
        BigDecimal wrongMax = TickSize.roundStop("BUY", rawWindow.stopMax());
        assertThat(wrongMax).isGreaterThan(rawWindow.stopMax());
    }

    @Test
    void roundedWindowIsSubsetOfRawWindow_sell() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");

        StopWindow rawWindow = sizer.stopWindow("SELL", price, atr, null);
        BigDecimal roundedMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedMax = roundBoundMaxInward(rawWindow.stopMax());

        assertThat(roundedMin).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(roundedMax).isLessThanOrEqualTo(rawWindow.stopMax());

        // Mirror: TickSize.roundStop for SELL always rounds FLOOR (toward the entry), which
        // widens stopMin (the near/tight bound for SELL) instead of narrowing it.
        BigDecimal wrongMin = TickSize.roundStop("SELL", rawWindow.stopMin());
        assertThat(wrongMin).isLessThan(rawWindow.stopMin());
    }

    // ---- Hazard 1 (Task-1 review): entry rounds down, stop rounds up (BUY) -> they converge.
    // price 100.009 -> 100.00, proposed stop 100.005 -> 100.01: the rounded stop sits ABOVE the
    // rounded entry of a long. PositionSizer.java:59 computes rPerShare = price.subtract(stopPrice)
    // with no guard against this — pin that it is negative, and that OrderGuard's side check
    // (which runs before any window check) rejects it as NO_STOP.

    @Test
    void entryAndStopConvergeToInvertedOrder_buy() {
        BigDecimal roundedEntry = TickSize.roundEntry("BUY", bd("100.009"));
        BigDecimal roundedStop = TickSize.roundStop("BUY", bd("100.005"));
        assertThat(roundedEntry).usingComparator(BigDecimal::compareTo).isEqualTo(bd("100.00"));
        assertThat(roundedStop).usingComparator(BigDecimal::compareTo).isEqualTo(bd("100.01"));
        assertThat(roundedStop).isGreaterThan(roundedEntry); // stop above entry on a long

        Sizing sizing = sizer.size("BUY", roundedEntry, bd("1"), null, roundedStop, bd("1000"), BigDecimal.ONE);
        assertThat(sizing.rPerShare()).isNegative(); // unguarded at PositionSizer.java:59

        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), roundedEntry, roundedStop,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");
        assertThat(guard.ok()).isFalse();
        assertThat(guard.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    // ---- Hazard 2 (Task-1 review): roundStop pushes a proposed stop toward the TIGHT window
    // bound (BUY: stopMax = anchor). "Round the stop, round the bounds, THEN clamp" protects the
    // final stop ONLY if the window bounds are derived from the SAME (rounded) entry price that
    // is about to be passed to sizer.size(...) — this is the fix from the second review round:
    // deriving the bounds from the RAW price while size() is called with the ROUNDED price
    // produces two different windows (a rounding-delta-times-2.5 gap, since the anchor is
    // price ± 2.5*ATR), and the clamped stop can fall in the gap between them.
    //
    // orderPriceRounded = TickSize.roundEntry(side, price) is used for BOTH the window and the
    // size() call below — that is the normative rule.

    @Test
    void roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal orderPriceRounded = TickSize.roundEntry("BUY", price);

        // Window derived from the SAME price that size() below is called with.
        StopWindow window = sizer.stopWindow("BUY", orderPriceRounded, atr, null);

        BigDecimal proposedNearAnchor = bd("91.691"); // inside the raw (100.017-based) window
        BigDecimal roundedProposed = TickSize.roundStop("BUY", proposedNearAnchor);
        BigDecimal roundedBoundMin = roundBoundMinInward(window.stopMin());
        BigDecimal roundedBoundMax = roundBoundMaxInward(window.stopMax());
        BigDecimal clamped = clamp(roundedProposed, roundedBoundMin, roundedBoundMax);

        Sizing sizing = sizer.size("BUY", orderPriceRounded, atr, null, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), orderPriceRounded, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        // With the window and size() sharing the same price, the hazard is closed by the
        // ordering itself — no separate mechanism is needed.
        assertThat(guard.ok()).isTrue();
    }

    @Test
    void roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_sell() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal orderPriceRounded = TickSize.roundEntry("SELL", price);

        StopWindow window = sizer.stopWindow("SELL", orderPriceRounded, atr, null);

        BigDecimal proposedNearAnchor = bd("108.343"); // inside the raw (100.017-based) window, close to the anchor
        BigDecimal roundedProposed = TickSize.roundStop("SELL", proposedNearAnchor);
        BigDecimal roundedBoundMin = roundBoundMinInward(window.stopMin());
        BigDecimal roundedBoundMax = roundBoundMaxInward(window.stopMax());
        BigDecimal clamped = clamp(roundedProposed, roundedBoundMin, roundedBoundMax);

        Sizing sizing = sizer.size("SELL", orderPriceRounded, atr, null, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("SELL", sizing.qty(), orderPriceRounded, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        assertThat(guard.ok()).isTrue();
    }

    // ---- Price consistency itself, pinned directly, so Task 3 cannot regress it: if the window
    // bounds are (wrongly) derived from the RAW price while size() is called with the ROUNDED
    // price — the two-different-prices mistake the second review round found — the same clamped
    // stop that is valid under the (correctly) single-price version above is REJECTED. This is
    // deliberately the same fixture as the two tests above, differing only in which price feeds
    // stopWindow(...), so it is the test that fails loudest if someone "simplifies" the two calls
    // back to different prices.

    @Test
    void windowMustBeDerivedFromTheSamePriceThatSizeUses_buy() {
        BigDecimal price = bd("100.017"); // raw, NOT rounded
        BigDecimal atr = bd("3.33");
        BigDecimal orderPriceRounded = TickSize.roundEntry("BUY", price);

        // BUG UNDER TEST: bounds derived from the raw price...
        StopWindow windowFromRawPrice = sizer.stopWindow("BUY", price, atr, null);

        BigDecimal proposedNearAnchor = bd("91.691");
        BigDecimal roundedProposed = TickSize.roundStop("BUY", proposedNearAnchor);
        BigDecimal roundedBoundMin = roundBoundMinInward(windowFromRawPrice.stopMin());
        BigDecimal roundedBoundMax = roundBoundMaxInward(windowFromRawPrice.stopMax());
        BigDecimal clamped = clamp(roundedProposed, roundedBoundMin, roundedBoundMax);

        // ...while size() is called with the ROUNDED price, as it must be (it is the order price).
        Sizing sizing = sizer.size("BUY", orderPriceRounded, atr, null, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), orderPriceRounded, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        // Two different prices feeding the two windows -> the clamped stop falls in the gap
        // between them and is rejected, even though it is a perfectly valid stop.
        assertThat(guard.ok()).isFalse();
        assertThat(guard.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    // ---- Pins recommendation (i): PositionSizer.size() always recomputes its window from raw
    // ATR/swing-low (see PositionSizer.java:51-52) and therefore always returns RAW
    // stopMin()/stopMax() — there is no way to inject pre-rounded bounds into it. The controller
    // must therefore pass ITS OWN rounded bounds (roundBoundMinInward/roundBoundMaxInward on the
    // window from sizer.stopWindow(...)) to OrderGuard.check at :613, never sizing.stopMin()/
    // stopMax(). This fixture is constructed so the two disagree: the clamped stop is valid
    // against the rounded bounds but NOT against sizing's raw ones, so a controller that
    // (incorrectly) reused sizing.stopMin()/stopMax() would reject a stop that should be
    // accepted.

    @Test
    void orderGuardMustUseTheControllersRoundedBounds_notSizingsRawOnes() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);
        BigDecimal roundedBoundMin = roundBoundMinInward(rawWindow.stopMin());
        BigDecimal roundedBoundMax = roundBoundMaxInward(rawWindow.stopMax());

        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);
        // Tight proposal clamps to the rounded stopMax (the tight/anchor bound for BUY).
        BigDecimal clamped = clamp(bd("99"), roundedBoundMin, roundedBoundMax);

        Sizing sizing = sizer.size("BUY", roundedEntry, atr, null, clamped, bd("1000"), BigDecimal.ONE);
        // Precondition: raw (sizing) bounds and the controller's rounded bounds genuinely
        // differ for this fixture — otherwise the test would prove nothing.
        assertThat(sizing.stopMax()).usingComparator(BigDecimal::compareTo).isNotEqualTo(roundedBoundMax);

        // The clamp target is only valid against the controller's OWN rounded bounds.
        OrderGuard.Result guardWithRoundedBounds = orderGuard.check("BUY", sizing.qty(), roundedEntry, clamped,
                roundedBoundMin, roundedBoundMax, "depot-1", "depot-1");
        assertThat(guardWithRoundedBounds.ok()).isTrue();

        // Pin: passing sizing.stopMin()/stopMax() instead (the mistake this test guards against)
        // must not silently start passing too — that would mean the two windows collapsed back
        // together and this fixture stopped being a valid trap. Today it correctly demonstrates
        // the discrepancy the next task must not reintroduce: sizing's raw bounds reject the
        // very stop the controller's rounded bounds just accepted.
        OrderGuard.Result guardWithSizingBounds = orderGuard.check("BUY", sizing.qty(), roundedEntry, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");
        assertThat(guardWithSizingBounds.ok()).isFalse();
        assertThat(guardWithSizingBounds.reason()).isEqualTo(RejectReason.NO_STOP);
    }
}
