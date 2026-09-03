package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buffer arithmetic, in one place. Every rule here exists because breaking it silently
 * changes what the broker holds: a stop that crosses the logical level makes the backstop the
 * decision, a non-positive stop is not an order, a cap violation gets the whole bracket rejected,
 * and a non-monotonic ratchet moves a live leg AGAINST the position.
 */
class BrokerStopTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static final BigDecimal BUFFER = BigDecimal.ONE;
    /** Cap wide enough never to bind, for the tests that are not about the cap. */
    private static final BigDecimal NO_CAP = bd("0.99");

    // ---------------------------------------------------------------- forEntry

    /** Test 8. Mutation: drop the min (BUY) / max (SELL) against the logical stop. A negative or
     *  zero atrEff, or a rounding step that overshoots, would otherwise put the broker leg on the
     *  wrong side of the level that decides. */
    @Test
    void neverCrossesLogicalStop() {
        // A negative atrEff would push the "buffered" BUY stop ABOVE the logical stop.
        BrokerStop.Result buy = BrokerStop.forEntry("BUY", bd("95.00"), bd("-2.00"), BUFFER,
                bd("100.00"), NO_CAP);
        assertThat(buy.price()).isLessThanOrEqualTo(bd("95.00"));

        BrokerStop.Result sell = BrokerStop.forEntry("SELL", bd("105.00"), bd("-2.00"), BUFFER,
                bd("100.00"), NO_CAP);
        assertThat(sell.price()).isGreaterThanOrEqualTo(bd("105.00"));

        // And the ordinary case still buffers away from the logical stop.
        BrokerStop.Result ordinary = BrokerStop.forEntry("BUY", bd("95.00"), bd("2.00"), BUFFER,
                bd("100.00"), NO_CAP);
        assertThat(ordinary.price()).isEqualByComparingTo("93.00");
    }

    /** Test 9. Mutation: drop the clamp. A buffer wider than the stop itself produces a negative
     *  price, which is not an order the broker can hold. */
    @Test
    void neverNonPositive() {
        BrokerStop.Result r = BrokerStop.forEntry("BUY", bd("4.00"), bd("6.00"), BUFFER,
                bd("10.00"), NO_CAP);

        assertThat(r.price()).isGreaterThan(BigDecimal.ZERO);
        assertThat(r.price()).isEqualByComparingTo("2.00");   // max(-2.00, 4.00 * 0.5)
        assertThat(r.clamped()).isTrue();
        assertThat(r.price()).isLessThanOrEqualTo(bd("4.00"));
    }

    /** Test 10. Mutation: any re-rounding at all on the identity path. StopWindowRounding's
     *  degenerate branch (:82-90) can hand over an UNROUNDED stop, and with buffer 0 the bracket
     *  must receive that exact value — this is the mechanical proof the feature is additive. */
    @Test
    void bufferZeroIsIdentityEvenForUnroundedInput() {
        BigDecimal unrounded = bd("94.99512345");

        BrokerStop.Result r = BrokerStop.forEntry("BUY", unrounded, bd("2.00"), BigDecimal.ZERO,
                bd("100.00"), bd("0.20"));

        assertThat(r.price()).isEqualTo(unrounded);   // isEqualTo, NOT isEqualByComparingTo:
                                                      // the scale must survive untouched too
        assertThat(r.clamped()).isFalse();
        assertThat(r.capped()).isFalse();
        assertThat(r.lags()).isFalse();
    }

    /** Test 11a. Mutation: ignore the cap. The buffer must shrink so the TOTAL distance stays
     *  inside maxDistancePct, and the flag must say so. */
    @Test
    void entryCapShrinksBufferBeforeLogicalStop() {
        // entry 100, cap 20 % -> floor 80.00. Logical stop 85 is inside; the buffer would take it
        // to 85 - 1 * 8 = 77, outside. The buffer shrinks to the cap floor.
        BrokerStop.Result r = BrokerStop.forEntry("BUY", bd("85.00"), bd("8.00"), BUFFER,
                bd("100.00"), bd("0.20"));

        assertThat(r.price()).isEqualByComparingTo("80.00");
        assertThat(r.capped()).isTrue();
        assertThat(r.price()).isLessThanOrEqualTo(bd("85.00"));
    }

    /** Test 11b. Mutation: cap the LOGICAL stop. When the logical stop alone already sits beyond
     *  the band, the broker stop equals it and today's behaviour applies — Agora's far-stop
     *  fallback is the safety net beyond the band, not a tightened stop we never chose. */
    @Test
    void logicalStopBeyondCapIsSentUnbuffered() {
        // entry 100, cap 20 % -> floor 80.00. Logical stop 75 is already beyond it.
        BrokerStop.Result r = BrokerStop.forEntry("BUY", bd("75.00"), bd("8.00"), BUFFER,
                bd("100.00"), bd("0.20"));

        assertThat(r.price()).isEqualByComparingTo("75.00");
        assertThat(r.capped()).isTrue();
    }

    /** SELL mirror of the cap. Mutation: use the BUY floor formula on SELL. */
    @Test
    void entryCapMirrorsOnSell() {
        // entry 100, cap 20 % -> ceiling 120.00. Logical 115 + 1 * 8 = 123 is outside.
        BrokerStop.Result r = BrokerStop.forEntry("SELL", bd("115.00"), bd("8.00"), BUFFER,
                bd("100.00"), bd("0.20"));

        assertThat(r.price()).isEqualByComparingTo("120.00");
        assertThat(r.capped()).isTrue();

        BrokerStop.Result beyond = BrokerStop.forEntry("SELL", bd("125.00"), bd("8.00"), BUFFER,
                bd("100.00"), bd("0.20"));
        assertThat(beyond.price()).isEqualByComparingTo("125.00");
        assertThat(beyond.capped()).isTrue();
    }

    /** Entry rounding is TickSize.roundStop — toward the entry, exactly as for initial stops.
     *  Mutation: round away from the entry (which would widen the resting risk). */
    @Test
    void entryRoundsTowardEntryOnTheTickGrid() {
        // BUY: 95.00 - 1 * 2.006 = 92.994 -> CEILING to 93.00 (toward the entry). Rounding AWAY
        // from the entry would give 92.99 and widen the resting risk by a cent.
        BrokerStop.Result buy = BrokerStop.forEntry("BUY", bd("95.00"), bd("2.006"), BUFFER,
                bd("100.00"), NO_CAP);
        assertThat(buy.price()).isEqualByComparingTo("93.00");

        // SELL: 105.00 + 1 * 2.006 = 107.006 -> FLOOR to 107.00.
        BrokerStop.Result sell = BrokerStop.forEntry("SELL", bd("105.00"), bd("2.006"), BUFFER,
                bd("100.00"), NO_CAP);
        assertThat(sell.price()).isEqualByComparingTo("107.00");
    }

    // -------------------------------------------------------------- forRatchet

    /** Test 12. Mutation: drop the max against the previous broker stop. ATR expanding faster than
     *  the high would otherwise walk the LIVE protective leg DOWN — the one thing this design must
     *  never do. */
    @Test
    void ratchetIsMonotonic() {
        BigDecimal previous = bd("45.34");
        // chandelier 45.45 (previous + 0.11), atrEff 2.20 (previous run's 2.00 + 0.20)
        BrokerStop.Result r = BrokerStop.forRatchet("BUY", bd("45.45"), bd("2.20"), BUFFER,
                previous, bd("45.90"));

        assertThat(r.price()).isGreaterThanOrEqualTo(previous);
        assertThat(r.price()).isEqualByComparingTo("45.34");
        assertThat(r.lags()).isTrue();
    }

    /** The ordinary case: the buffered chandelier improves on the previous broker stop, so it is
     *  sent and nothing lags. */
    @Test
    void ratchetSendsTheBufferedChandelierWhenItImproves() {
        BrokerStop.Result r = BrokerStop.forRatchet("BUY", bd("50.00"), bd("2.00"), BUFFER,
                bd("45.34"), bd("48.00"));

        assertThat(r.price()).isEqualByComparingTo("48.00");
        assertThat(r.lags()).isFalse();
    }

    /** SELL mirror of monotonicity: the broker leg may only ever come DOWN on a short. */
    @Test
    void ratchetIsMonotonicOnSell() {
        BrokerStop.Result r = BrokerStop.forRatchet("SELL", bd("54.55"), bd("2.20"), BUFFER,
                bd("54.66"), bd("54.10"));

        assertThat(r.price()).isLessThanOrEqualTo(bd("54.66"));
        assertThat(r.price()).isEqualByComparingTo("54.66");
        assertThat(r.lags()).isTrue();
    }

    /** Test 13. Mutation: use TickSize.roundStop on the ratchet path. The ratchet rounds AWAY from
     *  the entry (FLOOR for BUY, CEILING for SELL) — the same direction computeChandelier already
     *  uses — so rounding can never tighten a trailing stop into a premature stop-out. */
    @Test
    void ratchetRoundsAwayFromEntry() {
        // BUY: 50.00 - 1 * 2.006 = 47.994 -> FLOOR to 47.99. TickSize.roundStop would CEILING to
        // 48.00 and tighten the leg by a cent.
        BrokerStop.Result buy = BrokerStop.forRatchet("BUY", bd("50.00"), bd("2.006"), BUFFER,
                bd("40.00"), bd("45.00"));
        assertThat(buy.price()).isEqualByComparingTo("47.99");

        // SELL: 50.00 + 1 * 2.006 = 52.006 -> CEILING to 52.01.
        BrokerStop.Result sell = BrokerStop.forRatchet("SELL", bd("50.00"), bd("2.006"), BUFFER,
                bd("60.00"), bd("55.00"));
        assertThat(sell.price()).isEqualByComparingTo("52.01");
    }

    /** Test 14. Mutation: treat a null previousBrokerStop as ZERO. A row opened before V48 has no
     *  recorded broker stop, but its leg really does rest at active_stop — treating that as 0
     *  would let the very first post-V48 ratchet move a live leg DOWN by a whole buffer. */
    @Test
    void nullPreviousBrokerStopUsesActiveStopAsFloor() {
        BrokerStop.Result r = BrokerStop.forRatchet("BUY", bd("50.00"), bd("4.00"), BUFFER,
                null, bd("47.00"));

        assertThat(r.price()).isEqualByComparingTo("47.00");   // max(47.00, 50.00 - 4.00)
        assertThat(r.lags()).isTrue();
    }

    /** A legacy leg can rest ABOVE the logical stop and above the new chandelier: a partial
     *  ratchet confirms a price on the leg it managed to move without advancing active_stop, and
     *  V48 seeds broker_stop from that confirmed price. The monotonic floor must beat the "never
     *  crosses the logical stop" clamp here — the leg is left where it rests, tighter than the
     *  chandelier, and lags says so. Mutation: clamp the result to the chandelier (apply
     *  towardLogical AFTER the floor, or floor against activeStop instead of previousBrokerStop). */
    @Test
    void legacyLegAboveChandelierIsNeverMovedDown() {
        BrokerStop.Result r = BrokerStop.forRatchet("BUY", bd("50.00"), bd("2.00"), BUFFER,
                bd("51.30"), bd("47.00"));

        assertThat(r.price()).isEqualByComparingTo("51.30");   // not 48.00, and not 50.00
        assertThat(r.lags()).isTrue();
        assertThat(r.clamped()).isFalse();

        BrokerStop.Result sell = BrokerStop.forRatchet("SELL", bd("50.00"), bd("2.00"), BUFFER,
                bd("48.70"), bd("53.00"));

        assertThat(sell.price()).isEqualByComparingTo("48.70");
        assertThat(sell.lags()).isTrue();
    }

    /** Test 10, ratchet half. Mutation: any offset or rounding on the identity path. */
    @Test
    void ratchetBufferZeroReturnsTheChandelierUnchanged() {
        BigDecimal chandelier = bd("47.994");

        BrokerStop.Result r = BrokerStop.forRatchet("BUY", chandelier, bd("2.00"), BigDecimal.ZERO,
                bd("40.00"), bd("45.00"));

        assertThat(r.price()).isEqualTo(chandelier);
        assertThat(r.lags()).isFalse();
        assertThat(r.clamped()).isFalse();
    }

    /** The ratchet is NOT capped — the proximity band applies to entry and add-tranche only, where
     *  there is an entry price to measure against. Mutation: apply the cap here too. */
    @Test
    void ratchetNeverReportsCapped() {
        BrokerStop.Result r = BrokerStop.forRatchet("BUY", bd("50.00"), bd("20.00"), BUFFER,
                bd("10.00"), bd("30.00"));

        assertThat(r.capped()).isFalse();
        assertThat(r.price()).isEqualByComparingTo("30.00");
    }
}
