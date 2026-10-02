package de.visterion.dracul.executor;

import de.visterion.dracul.executor.KillLevelGuard.Mode;
import de.visterion.dracul.executor.KillLevelGuard.Outcome;
import de.visterion.dracul.executor.KillLevelGuard.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundaries of the place-entry kill-level guard (spec 2026-10-02 §3.4). Synthetic prices. */
class KillLevelGuardTest {

    private static final BigDecimal BASIS = new BigDecimal("50.00");
    private static final BigDecimal ATR = new BigDecimal("2.00");   // 0.5 x ATR = 1.00

    private static Result fresh(String level) {
        return KillLevelGuard.evaluate(level == null ? null : new BigDecimal(level), BASIS, ATR,
                "BUY", Mode.FRESH);
    }

    private static Result adopted(String level) {
        return KillLevelGuard.evaluate(level == null ? null : new BigDecimal(level), BASIS, ATR,
                "BUY", Mode.ADOPTED);
    }

    @Test
    void nullLevelKeepsNothing() {
        Result r = fresh(null);
        assertThat(r.outcome()).isEqualTo(Outcome.KEEP);
        assertThat(r.effectiveLevel()).isNull();
        assertThat(r.droppedReason()).isNull();
    }

    @Test
    void sellIsIgnored() {
        Result r = KillLevelGuard.evaluate(new BigDecimal("60"), BASIS, ATR, "SELL", Mode.FRESH);
        assertThat(r.outcome()).isEqualTo(Outcome.KEEP);
        assertThat(r.effectiveLevel()).isNull();
        assertThat(r.droppedReason()).isNull();
        assertThat(r.requestedLevel()).isEqualByComparingTo("60");
    }

    @Test
    void levelEqualToTheBasisRejectsAFreshPlacement() {
        Result r = fresh("50.00");
        assertThat(r.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(r.droppedReason()).isNull();
    }

    @Test
    void levelAboveTheBasisRejectsAFreshPlacement() {
        assertThat(fresh("50.01").outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void breachedLevelOnAnAdoptionIsDroppedNeverRejected() {
        for (String level : new String[] {"50.00", "57.30"}) {
            Result r = adopted(level);
            assertThat(r.outcome()).isEqualTo(Outcome.DROP_BREACHED_AT_ADOPTION);
            assertThat(r.effectiveLevel()).isNull();
            assertThat(r.droppedReason()).isEqualTo("breached_at_adoption");
        }
    }

    @Test
    void distanceExactlyHalfAnAtrIsKept() {
        Result r = fresh("49.00");                       // 50.00 - 49.00 = 1.00 == 0.5 x 2.00
        assertThat(r.outcome()).isEqualTo(Outcome.KEEP);
        assertThat(r.effectiveLevel()).isEqualByComparingTo("49.00");
    }

    @Test
    void distanceBelowHalfAnAtrIsDroppedAsTooTight() {
        Result r = fresh("49.01");                       // 0.99 < 1.00
        assertThat(r.outcome()).isEqualTo(Outcome.DROP_TOO_TIGHT);
        assertThat(r.effectiveLevel()).isNull();
        assertThat(r.droppedReason()).isEqualTo("too_tight");
        assertThat(adopted("49.01").outcome()).isEqualTo(Outcome.DROP_TOO_TIGHT);
    }

    @Test
    void comfortableDistanceIsKept() {
        Result r = adopted("45.00");
        assertThat(r.outcome()).isEqualTo(Outcome.KEEP);
        assertThat(r.effectiveLevel()).isEqualByComparingTo("45.00");
        assertThat(r.basis()).isEqualByComparingTo("50.00");
    }

    /** Same geometry as the spec's evidence case, re-expressed with synthetic numbers: a level
     *  at the pre-report close, 0.62 below the entry with an atr_effective of 1.60 — i.e. a level
     *  at ~0.39 ATR (well under the 0.5 ATR floor) — is too tight to arm. */
    @Test
    void preReportCloseJustUnderTheEntryIsTooTight() {
        Result r = KillLevelGuard.evaluate(new BigDecimal("23.38"), new BigDecimal("24.00"),
                new BigDecimal("1.60"), "BUY", Mode.FRESH);
        assertThat(r.outcome()).isEqualTo(Outcome.DROP_TOO_TIGHT);
    }

    @Test
    void missingAtrSkipsTheDistanceCheck() {
        Result r = KillLevelGuard.evaluate(new BigDecimal("49.99"), BASIS, null, "BUY", Mode.FRESH);
        assertThat(r.outcome()).isEqualTo(Outcome.KEEP);
        assertThat(r.effectiveLevel()).isEqualByComparingTo("49.99");
    }
}
