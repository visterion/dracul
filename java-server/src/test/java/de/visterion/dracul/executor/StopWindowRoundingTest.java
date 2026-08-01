package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the stop-window tick-rounding invariants of {@link StopWindowRounding}, the pure helper
 * that {@code ExecutorWebhookController} calls on the entry path (formerly lines 540-550 and
 * 613-614). {@link PositionSizer} and {@link OrderGuard} are unmodified — these tests exercise
 * the helper directly, plus the primitives it is built from, so every assertion binds to
 * production code.
 */
class StopWindowRoundingTest {

    private final PositionSizer sizer = new PositionSizer();
    private final OrderGuard orderGuard = new OrderGuard();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** Mirrors the clamp inside {@link StopWindowRounding} / (formerly) ExecutorWebhookController
     *  at :542-547 — used here only where a test needs to reconstruct the OLD (pre-helper)
     *  behavior for comparison. */
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
     * Mirrors {@link StopWindowRounding}'s private rounding of the bounds so tests can predict
     * its output without depending on its internals.
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
        BigDecimal orderPriceRounded = TickSize.roundEntry("BUY", price);

        StopWindow rawWindow = sizer.stopWindow("BUY", orderPriceRounded, atr, swingLow);
        assertThat(rawWindow.stopMax().subtract(rawWindow.stopMin()))
                .isLessThan(TickSize.tickFor(price)); // window narrower than one tick
        assertThat(roundBoundMinInward(rawWindow.stopMin()))
                .isGreaterThan(roundBoundMaxInward(rawWindow.stopMax())); // inward rounding inverts it

        // Proposal far outside the window on the low side, exactly as an LLM proposal that
        // ignores the risk layer would look.
        StopWindowRounding.Result result = StopWindowRounding.compute(
                "BUY", orderPriceRounded, atr, swingLow, bd("1.00"), sizer);

        assertThat(result.stop()).usingComparator(BigDecimal::compareTo).isEqualTo(RAW_CLAMPED_STOP_TODAY_BUY);

        Sizing sizing = sizer.size("BUY", orderPriceRounded, atr, swingLow, result.stop(), bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), orderPriceRounded, result.stop(),
                result.stopMin(), result.stopMax(), "depot-1", "depot-1");

        assertThat(guard.reason()).isNull(); // must not be NO_STOP from a rounding artifact
    }

    @Test
    void degenerateWindowFallsBackToTheRawClampedStop_sell() {
        BigDecimal price = bd("1.50");
        BigDecimal atr = bd("0.03");
        BigDecimal swingHigh = bd("1.601");
        BigDecimal orderPriceRounded = TickSize.roundEntry("SELL", price);

        StopWindow rawWindow = sizer.stopWindow("SELL", orderPriceRounded, atr, swingHigh);
        assertThat(rawWindow.stopMax().subtract(rawWindow.stopMin()))
                .isLessThan(TickSize.tickFor(price));
        assertThat(roundBoundMinInward(rawWindow.stopMin()))
                .isGreaterThan(roundBoundMaxInward(rawWindow.stopMax())); // inverted, same as the BUY mirror

        StopWindowRounding.Result result = StopWindowRounding.compute(
                "SELL", orderPriceRounded, atr, swingHigh, bd("2.00"), sizer);

        assertThat(result.stop()).usingComparator(BigDecimal::compareTo).isEqualTo(RAW_CLAMPED_STOP_TODAY_SELL);

        Sizing sizing = sizer.size("SELL", orderPriceRounded, atr, swingHigh, result.stop(), bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("SELL", sizing.qty(), orderPriceRounded, result.stop(),
                result.stopMin(), result.stopMax(), "depot-1", "depot-1");

        assertThat(guard.reason()).isNull();
    }

    // ---- P1: risk-per-share bound. The old claim "only SELL is affected" was wrong: for BUY,
    // PositionSizer.java:103 sets stopMax = anchor (the bound nearest the entry), so rounding
    // that bound inward moves the tightest permitted stop AWAY from the entry and
    // rPerShare = price.subtract(stopPrice) grows. Fixture: price 100.017 / ATR 3.33 (chosen so
    // neither the anchor nor the entry lands on a tick), proposal far tighter than the anchor so
    // the clamp binds at the tight bound on both sides.
    //
    // The ROUNDED-side window is derived (inside StopWindowRounding) from roundedEntry (the same
    // price passed to size() below) — not from the raw price. Deriving it from the raw price
    // would be exactly the two-price mistake StopWindowRounding exists to rule out.

    @Test
    void riskPerShareChangesByAtMostOneTick_buyBindingAtStopMax() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal proposed = bd("99"); // tighter than the anchor -> clamp binds at stopMax

        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);
        BigDecimal clampedRaw = clamp(proposed, rawWindow.stopMin(), rawWindow.stopMax());
        Sizing rawSizing = sizer.size("BUY", price, atr, null, clampedRaw, bd("1000"), BigDecimal.ONE);

        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);
        StopWindowRounding.Result result = StopWindowRounding.compute(
                "BUY", roundedEntry, atr, null, proposed, sizer);
        Sizing roundedSizing = sizer.size("BUY", roundedEntry, atr, null, result.stop(), bd("1000"), BigDecimal.ONE);

        BigDecimal delta = roundedSizing.rPerShare().subtract(rawSizing.rPerShare()).abs();
        assertThat(delta).isLessThanOrEqualTo(TickSize.tickFor(price));
    }

    // Not vacuous: unlike the BUY twin above, the <=1-tick envelope alone survives EITHER
    // direction of a stopMin bound-rounding mutation here (delta stays 0.005 whether stopMin
    // rounds CEILING or FLOOR for this fixture), so it would not catch a regression. Asserting
    // the exact rPerShare values closes that gap: mutating the inward bound rounding from
    // CEILING to FLOOR changes the rounded stop from 108.35 to 108.34, which the exact-value
    // assertion below catches (rPerShare 8.32 instead of 8.33) even though the envelope check
    // would still pass.
    @Test
    void riskPerShareChangesByAtMostOneTick_sellBindingAtStopMin() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal proposed = bd("101"); // tighter than the anchor -> clamp binds at stopMin

        StopWindow rawWindow = sizer.stopWindow("SELL", price, atr, null);
        BigDecimal clampedRaw = clamp(proposed, rawWindow.stopMin(), rawWindow.stopMax());
        Sizing rawSizing = sizer.size("SELL", price, atr, null, clampedRaw, bd("1000"), BigDecimal.ONE);
        assertThat(rawSizing.rPerShare()).usingComparator(BigDecimal::compareTo).isEqualTo(bd("8.325"));

        BigDecimal roundedEntry = TickSize.roundEntry("SELL", price);
        StopWindowRounding.Result result = StopWindowRounding.compute(
                "SELL", roundedEntry, atr, null, proposed, sizer);
        Sizing roundedSizing = sizer.size("SELL", roundedEntry, atr, null, result.stop(), bd("1000"), BigDecimal.ONE);
        assertThat(roundedSizing.rPerShare()).usingComparator(BigDecimal::compareTo).isEqualTo(bd("8.33"));

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
    // (TickSize.roundStop, the original bug from the first fix round) and asserts THAT violates
    // the subset property. That makes the property nontrivial — there is a real, easy-to-reach
    // wrong implementation this test would catch — rather than merely re-deriving what CEILING/
    // FLOOR already guarantee by definition. Uses a proposal inside the raw window so
    // StopWindowRounding takes the non-degenerate path and its returned bounds are the rounded
    // ones under test.

    @Test
    void roundedWindowIsSubsetOfRawWindow_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);

        StopWindow rawWindow = sizer.stopWindow("BUY", roundedEntry, atr, null);
        BigDecimal proposalInsideWindow = rawWindow.stopMax(); // anchor itself, well inside the raw window

        StopWindowRounding.Result result = StopWindowRounding.compute(
                "BUY", roundedEntry, atr, null, proposalInsideWindow, sizer);

        assertThat(result.stopMin()).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(result.stopMax()).isLessThanOrEqualTo(rawWindow.stopMax());
        assertThat(result.stopMin()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(roundBoundMinInward(rawWindow.stopMin()));
        assertThat(result.stopMax()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(roundBoundMaxInward(rawWindow.stopMax()));

        // The plausible wrong implementation (TickSize.roundStop applied to both bounds) widens
        // stopMax for BUY because it always rounds toward the entry (CEILING for BUY), not
        // toward the window's interior.
        BigDecimal wrongMax = TickSize.roundStop("BUY", rawWindow.stopMax());
        assertThat(wrongMax).isGreaterThan(rawWindow.stopMax());
    }

    @Test
    void roundedWindowIsSubsetOfRawWindow_sell() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal roundedEntry = TickSize.roundEntry("SELL", price);

        StopWindow rawWindow = sizer.stopWindow("SELL", roundedEntry, atr, null);
        BigDecimal proposalInsideWindow = rawWindow.stopMin(); // anchor itself, well inside the raw window

        StopWindowRounding.Result result = StopWindowRounding.compute(
                "SELL", roundedEntry, atr, null, proposalInsideWindow, sizer);

        assertThat(result.stopMin()).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(result.stopMax()).isLessThanOrEqualTo(rawWindow.stopMax());
        assertThat(result.stopMin()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(roundBoundMinInward(rawWindow.stopMin()));
        assertThat(result.stopMax()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(roundBoundMaxInward(rawWindow.stopMax()));

        // Mirror: TickSize.roundStop for SELL always rounds FLOOR (toward the entry), which
        // widens stopMin (the near/tight bound for SELL) instead of narrowing it.
        BigDecimal wrongMin = TickSize.roundStop("SELL", rawWindow.stopMin());
        assertThat(wrongMin).isLessThan(rawWindow.stopMin());
    }

    // ---- Hazard 1 (Task-1 review): entry rounds down, stop rounds up (BUY) -> they converge.
    // price 100.009 -> 100.00, proposed stop 100.005 -> 100.01: the rounded stop sits ABOVE the
    // rounded entry of a long. PositionSizer.java:59 computes rPerShare = price.subtract(stopPrice)
    // with no guard against this — pin that it is negative (load-bearing assertion). OrderGuard
    // rejects it as NO_STOP either way (window check or side check — with a real, non-null
    // window here, either one alone is sufficient, so this first assertion does NOT by itself
    // prove which check fired). The second assertion below, with stopMin/stopMax forced to null
    // so the window check is structurally skipped (OrderGuard.java:56-59), is what actually pins
    // that the side check (OrderGuard.java:48-49) fires on its own.
    //
    // Independent of StopWindowRounding on purpose: it demonstrates that even a
    // correctly-tick-rounded entry/stop pair, considered in isolation from the window/clamp
    // pipeline, can still invert — the reason OrderGuard's own side check remains load-bearing.

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

        // Proves the side check alone rejects this: with a null window, OrderGuard.java:56-59
        // is structurally unreachable, so only the side check (:48-49) can be responsible for
        // this NO_STOP.
        OrderGuard.Result guardWithNullWindow = orderGuard.check("BUY", sizing.qty(), roundedEntry, roundedStop,
                null, null, "depot-1", "depot-1");
        assertThat(guardWithNullWindow.ok()).isFalse();
        assertThat(guardWithNullWindow.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    // ---- Hazard 2 (Task-1 review): rounding pushes a proposed stop toward the TIGHT window
    // bound (BUY: stopMax = anchor). "Round the stop, round the bounds, THEN clamp" protects the
    // final stop ONLY if the window bounds are derived from the SAME (rounded) entry price that
    // is about to be passed to sizer.size(...) — this is exactly what StopWindowRounding
    // encapsulates: deriving the bounds from the RAW price while size() is called with the
    // ROUNDED price would produce two different windows (a rounding-delta-times-2.5 gap, since
    // the anchor is price +/- 2.5*ATR), and the clamped stop could fall in the gap between them.

    @Test
    void roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal orderPriceRounded = TickSize.roundEntry("BUY", price);

        BigDecimal proposedNearAnchor = bd("91.691"); // inside the raw (100.017-based) window
        StopWindowRounding.Result result = StopWindowRounding.compute(
                "BUY", orderPriceRounded, atr, null, proposedNearAnchor, sizer);

        Sizing sizing = sizer.size("BUY", orderPriceRounded, atr, null, result.stop(), bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), orderPriceRounded, result.stop(),
                result.stopMin(), result.stopMax(), "depot-1", "depot-1");

        // With the window and size() sharing the same price, the hazard is closed by the
        // ordering itself — no separate mechanism is needed.
        assertThat(guard.ok()).isTrue();
    }

    // Weaker than its BUY twin: for this fixture, the SELL gap between the raw-price and
    // rounded-price windows happens not to straddle a tick, so this test alone would not catch
    // the two-price defect the way :262 does for BUY. Kept as the SELL mirror of the intended
    // (correct) behavior; not relied on as the two-price regression guard.
    @Test
    void roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_sell() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        BigDecimal orderPriceRounded = TickSize.roundEntry("SELL", price);

        BigDecimal proposedNearAnchor = bd("108.343"); // inside the raw (100.017-based) window, close to the anchor
        StopWindowRounding.Result result = StopWindowRounding.compute(
                "SELL", orderPriceRounded, atr, null, proposedNearAnchor, sizer);

        Sizing sizing = sizer.size("SELL", orderPriceRounded, atr, null, result.stop(), bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("SELL", sizing.qty(), orderPriceRounded, result.stop(),
                result.stopMin(), result.stopMax(), "depot-1", "depot-1");

        assertThat(guard.ok()).isTrue();
    }

    // ---- Documentation, not enforcement. This test asserts guard.ok() == FALSE, i.e. it
    // demonstrates what happened when the two-different-prices mistake was present (before
    // StopWindowRounding existed) — it deliberately reconstructs that OLD bug manually, since
    // StopWindowRounding itself structurally cannot make this mistake (it only ever takes ONE
    // price and derives the window from it). The actual enforcement against reintroducing this
    // mistake is `roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_buy` above,
    // whose assertion (guard.ok() == TRUE) is what would fire if StopWindowRounding regressed to
    // ever mixing prices. This test is kept anyway, as a side-by-side comparison that makes the
    // contrast legible for a human reading both tests together — not a safety net on its own.

    @Test
    void mixingRawAndRoundedPricesIsRejectedToday_documentation() {
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

    // ---- Note on "helper bounds vs sizing bounds": an earlier version of this suite pinned a
    // fixture where OrderGuard.check accepted StopWindowRounding's rounded bounds but rejected
    // sizing.stopMin()/stopMax(). That fixture only worked by deriving the window from a
    // DIFFERENT (raw) price than sizer.size() used — i.e. it relied on the very two-price bug
    // this task closes. Once both are derived from the identical rounded price (rule 2, enforced
    // structurally by StopWindowRounding), sizing.stopMin()/stopMax() IS the raw window
    // (`sizingStopMinEqualsStopWindowStopMin` above), and the rounded bounds are always a subset
    // of it (`roundedWindowIsSubsetOfRawWindow_*` above) — so a stop valid against the rounded
    // bounds is now provably always valid against sizing's raw bounds too, and no fixture can
    // demonstrate the opposite without reintroducing the two-price mistake. The controller must
    // still pass StopWindowRounding's own bounds to OrderGuard.check, not sizing's — not because
    // today's fixtures can catch a swap, but because sizing's bounds are undefined/inconsistent
    // in the degenerate-window branch (StopWindowRounding.compute returns the RAW window there,
    // which does equal sizing's, but only by that branch's construction) and because relying on
    // an accidental equality instead of the documented contract is exactly the kind of coupling
    // that broke before.
}
