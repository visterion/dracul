package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure decision-logic tests for tranche-2 eligibility. The hard gate — never eligible below
 *  entry, no averaging down — is checked first and wins over every other condition, including a
 *  reinforcing signal. */
class Tranche2DetectorTest {

    private final Tranche2Detector detector = new Tranche2Detector();

    private ExecutorPosition position(int tranche, String side, BigDecimal entryPrice,
            BigDecimal initialStop, BigDecimal entryDayHigh, String symbol, String status) {
        return position(tranche, side, entryPrice, initialStop, entryDayHigh, symbol, status,
                "2026-07-02T00:00:00Z");
    }

    /** {@code entryFilledAt} null = the broker has not confirmed a holding for this position. */
    private ExecutorPosition position(int tranche, String side, BigDecimal entryPrice,
            BigDecimal initialStop, BigDecimal entryDayHigh, String symbol, String status,
            String entryFilledAt) {
        return new ExecutorPosition(1L, "c", symbol, side, BigDecimal.TEN, entryPrice, initialStop,
                initialStop, tranche, null, List.of(), "sig-1", "agent", "2026-07-01", null, status,
                "brk-1", null, null, 0, null, null, null, null, "stop-1", null, entryDayHigh, null, null, 0, null, null,
                null, null, null, null, false, null, entryFilledAt);
    }

    private ExecutorSignal signal(String symbol, String direction, String mechanism) {
        return new ExecutorSignal("sig-x", "source", "v1", symbol, direction, 0.8, mechanism,
                List.of(), "6m", null, "PENDING", "2026-07-01T00:00:00Z");
    }

    @Test
    void rConfirmed_atExactlyPlusOneR_eligible() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN");

        Tranche2Detector.Tranche2Status status = detector.detect(p, new BigDecimal("110"), List.of(), "PEAD");

        assertThat(status.eligible()).isTrue();
        assertThat(status.reason()).isEqualTo("R_CONFIRMED");
    }

    @Test
    void belowEntry_neverEligible_evenWithReinforcingSignal() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN");
        List<ExecutorSignal> pendings = List.of(signal("ACME", "BUY", "SPIN_OFF"));

        Tranche2Detector.Tranche2Status status = detector.detect(p, new BigDecimal("99"), pendings, "PEAD");

        assertThat(status.eligible()).isFalse();
        assertThat(status.reason()).isNull();
    }

    /** Test 19. A new high alone is no longer evidence. Prod: a position doubled on
     *  "NEW_HIGH" while it sat at +0.08 R, and both tranches were stopped out together.
     *  Mutation: re-add the entryDayHigh branch. */
    @Test
    void newHighAloneIsNotEligible() {
        // Above entry, above the entry-day high, but under +1R and with no reinforcing signal.
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("101"), "ACME", "OPEN");

        Tranche2Detector.Tranche2Status status =
                detector.detect(p, new BigDecimal("105"), List.of(), "PEAD");

        assertThat(status.eligible()).isFalse();
        assertThat(status.reason()).isNull();
    }

    /** Test 19, SELL mirror. */
    @Test
    void newLowAloneIsNotEligibleOnSell() {
        ExecutorPosition p = position(1, "SELL", new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("99"), "ACME", "OPEN");

        Tranche2Detector.Tranche2Status status =
                detector.detect(p, new BigDecimal("95"), List.of(), "PEAD");

        assertThat(status.eligible()).isFalse();
    }

    /** Test 20. Never add to a position the broker has not filled — the second bracket would rest
     *  next to an unfilled first one and double the intended size the moment both fill.
     *  Mutation: drop the entryFilledAt check. Without it this position is at exactly +1R and
     *  would fire R_CONFIRMED. */
    @Test
    void unfilledEntryIsNotEligibleEvenAtOneR() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN", null);

        Tranche2Detector.Tranche2Status status =
                detector.detect(p, new BigDecimal("110"), List.of(), "PEAD");

        assertThat(status.eligible()).isFalse();
        assertThat(status.reason()).isNull();
    }

    /** Test 20, reinforcing-signal route. The precondition wins over EVERY route, not just R. */
    @Test
    void unfilledEntryIsNotEligibleEvenWithAReinforcingSignal() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN", null);
        List<ExecutorSignal> pendings = List.of(signal("ACME", "BUY", "SPIN_OFF"));

        assertThat(detector.detect(p, new BigDecimal("101"), pendings, "PEAD").eligible()).isFalse();
    }

    /** Test 21. Regression: with the entry filled, both surviving routes still fire.
     *  Mutation: make the entryFilledAt check reject everything. */
    @Test
    void rConfirmedAndReinforcingStillFireWhenFilled() {
        ExecutorPosition filled = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN", "2026-07-02T00:00:00Z");

        Tranche2Detector.Tranche2Status r =
                detector.detect(filled, new BigDecimal("110"), List.of(), "PEAD");
        assertThat(r.eligible()).isTrue();
        assertThat(r.reason()).isEqualTo("R_CONFIRMED");

        List<ExecutorSignal> pendings = List.of(signal("ACME", "BUY", "SPIN_OFF"));
        Tranche2Detector.Tranche2Status reinforcing =
                detector.detect(filled, new BigDecimal("101"), pendings, "PEAD");
        assertThat(reinforcing.eligible()).isTrue();
        assertThat(reinforcing.reason()).isEqualTo("REINFORCING_SIGNAL");
    }

    @Test
    void reinforcing_singlePendingWithDifferentMechanismThanPosition_eligible() {
        // price between entry and +1R (0.05R) and below entryDayHigh -> only reinforcement can qualify
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("101"), "ACME", "OPEN");

        Tranche2Detector.Tranche2Status reinforcing = detector.detect(p, new BigDecimal("100.5"),
                List.of(signal("ACME", "BUY", "SPIN_OFF")), "PEAD");
        assertThat(reinforcing.eligible()).isTrue();
        assertThat(reinforcing.reason()).isEqualTo("REINFORCING_SIGNAL");
    }

    @Test
    void reinforcing_pendingSameMechanismAsPosition_notEligible() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("101"), "ACME", "OPEN");

        // pending mechanism matches the position's own mechanism -> not independent -> not eligible
        Tranche2Detector.Tranche2Status sameMechanism = detector.detect(p, new BigDecimal("100.5"),
                List.of(signal("ACME", "BUY", "PEAD")), "PEAD");
        assertThat(sameMechanism.eligible()).isFalse();
    }

    @Test
    void reinforcing_unknownPositionMechanism_routeUnavailableEvenWithDifferingPending() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("101"), "ACME", "OPEN");

        // positionMechanism unknown (null) -> reinforcing route is conservatively unavailable
        Tranche2Detector.Tranche2Status status = detector.detect(p, new BigDecimal("100.5"),
                List.of(signal("ACME", "BUY", "SPIN_OFF")), null);
        assertThat(status.eligible()).isFalse();
    }

    @Test
    void reinforcing_wrongDirection_notEligible() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("101"), "ACME", "OPEN");

        Tranche2Detector.Tranche2Status wrongDirection = detector.detect(p, new BigDecimal("100.5"),
                List.of(signal("ACME", "SELL", "SPIN_OFF")), "PEAD");
        assertThat(wrongDirection.eligible()).isFalse();
    }

    @Test
    void tranche2Position_notEligible() {
        ExecutorPosition p = position(2, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN");

        Tranche2Detector.Tranche2Status status = detector.detect(p, new BigDecimal("110"), List.of(), "PEAD");

        assertThat(status.eligible()).isFalse();
    }

    @Test
    void sellMirror_rConfirmedAndNeverAboveEntry() {
        ExecutorPosition p = position(1, "SELL", new BigDecimal("100"), new BigDecimal("110"),
                null, "ACME", "OPEN");

        Tranche2Detector.Tranche2Status confirmed = detector.detect(p, new BigDecimal("90"), List.of(), "PEAD");
        assertThat(confirmed.eligible()).isTrue();
        assertThat(confirmed.reason()).isEqualTo("R_CONFIRMED");

        // price above entry for a short -> never eligible (no averaging down, mirrored)
        Tranche2Detector.Tranche2Status aboveEntry = detector.detect(p, new BigDecimal("100.5"),
                List.of(signal("ACME", "SELL", "SPIN_OFF")), "PEAD");
        assertThat(aboveEntry.eligible()).isFalse();
    }

    @Test
    void notOpenOrNullPrice_notEligible() {
        ExecutorPosition closed = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "CLOSED");
        assertThat(detector.detect(closed, new BigDecimal("110"), List.of(), "PEAD").eligible()).isFalse();

        ExecutorPosition open = position(1, "BUY", new BigDecimal("100"), new BigDecimal("90"),
                null, "ACME", "OPEN");
        assertThat(detector.detect(open, null, List.of(), "PEAD").eligible()).isFalse();
    }

    @Test
    void nullInitialStop_doesNotThrow() {
        ExecutorPosition p = position(1, "BUY", new BigDecimal("100"), null, null, "ACME", "OPEN");

        Tranche2Detector.Tranche2Status status = detector.detect(p, new BigDecimal("110"), List.of(), "PEAD");

        assertThat(status.eligible()).isFalse();
    }
}
