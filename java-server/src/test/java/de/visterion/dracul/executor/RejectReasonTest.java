package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RejectReasonTest {

    /** The exact set of capacity/rate vetos that leave the signal PENDING for the current run
     *  (place_entry does not retire it; the LLM's submit_decision SKIP normally does, see
     *  RejectReason's Javadoc — in-executor deferral is SP2b). */
    private static final Set<RejectReason> EXPECTED_TRANSIENT = EnumSet.of(
            RejectReason.PACE_LIMIT,
            RejectReason.MAX_POSITIONS,
            RejectReason.MECHANISM_BUDGET,
            RejectReason.BUDGET,
            RejectReason.HEAT_LIMIT,
            RejectReason.COOLDOWN,
            RejectReason.PATTERN_GATE,
            RejectReason.DATA_UNAVAILABLE);

    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void isTransient_matchesExactlyTheCapacityRateSet(RejectReason reason) {
        assertThat(reason.isTransient())
                .as("isTransient() for " + reason)
                .isEqualTo(EXPECTED_TRANSIENT.contains(reason));
    }

    @Test
    void mechanismBudgetIsTransient() {
        assertThat(RejectReason.MECHANISM_BUDGET.isTransient()).isTrue();
    }

    @Test
    void terminalSamplesAreNotTransient() {
        assertThat(RejectReason.LOW_CONFIDENCE.isTransient()).isFalse();
        assertThat(RejectReason.SCHEMA_INVALID.isTransient()).isFalse();
        assertThat(RejectReason.SIGNAL_EXPIRED.isTransient()).isFalse();
        assertThat(RejectReason.CHASED_AWAY.isTransient()).isFalse();
        assertThat(RejectReason.CONTRADICTION.isTransient()).isFalse();
    }

    /** RISK_TOO_WIDE is TERMINAL. A signal whose stop distance exceeds the whole per-trade risk
     *  budget is structurally untradeable at this budget — nothing about waiting a run changes it,
     *  and a transient classification would leave it silently PENDING until SIGNAL_EXPIRED.
     *  A fresh signal with a tighter stop is a NEW signal.
     *  Mutation: add RISK_TOO_WIDE to the TRANSIENT set (which also reddens the @EnumSource test
     *  above, by construction). */
    @Test
    void riskTooWideIsTerminal() {
        assertThat(RejectReason.RISK_TOO_WIDE.isTransient()).isFalse();
        assertThat(EXPECTED_TRANSIENT).doesNotContain(RejectReason.RISK_TOO_WIDE);
    }

    /** A missing upstream datum is an OUTAGE, not a verdict on the signal: an Agora hiccup that
     *  drops price/ATR/ADV20/sector for one run must not retire a signal the next run could
     *  evaluate perfectly well. Bounded by SIGNAL_EXPIRED (catalog #3), so a permanently
     *  data-less instrument still leaves the queue -- it just takes the expiry to do it. */
    @Test
    void dataUnavailableIsTransient() {
        assertThat(RejectReason.DATA_UNAVAILABLE.isTransient()).isTrue();
    }

    @Test
    void transientSetHasExactlyEightMembers() {
        assertThat(EXPECTED_TRANSIENT).hasSize(8);
    }
}
