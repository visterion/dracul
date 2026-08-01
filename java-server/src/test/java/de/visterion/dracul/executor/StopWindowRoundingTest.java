package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
        BigDecimal roundedMin = TickSize.roundStop("BUY", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("BUY", rawWindow.stopMax());

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
        BigDecimal roundedMin = TickSize.roundStop("SELL", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("SELL", rawWindow.stopMax());

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
        BigDecimal roundedMin = TickSize.roundStop("BUY", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("BUY", rawWindow.stopMax());
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
        BigDecimal roundedMin = TickSize.roundStop("SELL", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("SELL", rawWindow.stopMax());
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

    // ---- Rounding must never widen the window.

    @Test
    void roundedWindowIsSubsetOfRawWindow_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");

        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);
        BigDecimal roundedMin = TickSize.roundStop("BUY", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("BUY", rawWindow.stopMax());

        assertThat(roundedMin).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(roundedMax).isLessThanOrEqualTo(rawWindow.stopMax());
    }

    @Test
    void roundedWindowIsSubsetOfRawWindow_sell() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");

        StopWindow rawWindow = sizer.stopWindow("SELL", price, atr, null);
        BigDecimal roundedMin = TickSize.roundStop("SELL", rawWindow.stopMin());
        BigDecimal roundedMax = TickSize.roundStop("SELL", rawWindow.stopMax());

        assertThat(roundedMin).isGreaterThanOrEqualTo(rawWindow.stopMin());
        assertThat(roundedMax).isLessThanOrEqualTo(rawWindow.stopMax());
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
    // bound (BUY: stopMax = anchor). "Round the stop, round the bounds, THEN clamp" is supposed
    // to protect the final stop from ending up outside the window OrderGuard actually checks
    // (sizing.stopMin()/stopMax(), which PositionSizer always derives fresh from the RAW,
    // unrounded ATR/swing-low — window bounds are never rounded inside PositionSizer itself).
    //
    // Empirically it does NOT protect it: rounding a proposal that sits just inside the raw
    // window (91.691, raw stopMax = 91.692) pushes it to 91.70, which is inside the *rounded*
    // bounds (also rounded up to 91.70) but OUTSIDE the raw window PositionSizer/OrderGuard
    // validate against -> OrderGuard rejects with NO_STOP. This is a genuine finding, not a
    // wrong assertion: the ordering "round the stop, round the bounds, THEN clamp" is
    // insufficient by itself; the next task must round against and clamp into the SAME window
    // that ends up in Sizing, or reconcile the two some other way.

    @Test
    void roundStopTowardTightBoundStaysInsideTheWindowOrderGuardChecks_buy() {
        BigDecimal price = bd("100.017");
        BigDecimal atr = bd("3.33");
        StopWindow rawWindow = sizer.stopWindow("BUY", price, atr, null);

        BigDecimal proposedNearAnchor = bd("91.691"); // inside raw window, close to stopMax=91.692
        BigDecimal roundedProposed = TickSize.roundStop("BUY", proposedNearAnchor);
        BigDecimal roundedBoundMin = TickSize.roundStop("BUY", rawWindow.stopMin());
        BigDecimal roundedBoundMax = TickSize.roundStop("BUY", rawWindow.stopMax());
        BigDecimal clamped = clamp(roundedProposed, roundedBoundMin, roundedBoundMax);

        BigDecimal roundedEntry = TickSize.roundEntry("BUY", price);
        Sizing sizing = sizer.size("BUY", roundedEntry, atr, null, clamped, bd("1000"), BigDecimal.ONE);
        OrderGuard.Result guard = orderGuard.check("BUY", sizing.qty(), roundedEntry, clamped,
                sizing.stopMin(), sizing.stopMax(), "depot-1", "depot-1");

        // Intended behavior: rounding + clamping protects the stop, so the guard passes.
        assertThat(guard.ok()).isTrue();
    }
}
