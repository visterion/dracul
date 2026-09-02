package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole domain rule set of the backfill. No database, no HTTP — the tranche rule
 * (spec §3.3) and the anchor-day reconciliation (spec §3.4) are pure functions over rows.
 */
class PositionLedgerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 3, 2);  // Monday
    private static final LocalDate D2 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 4);
    private static final LocalDate D5 = LocalDate.of(2026, 3, 6);

    private static BookPosition single(String symbol, BigDecimal qty, BigDecimal price,
                                       LocalDate entry, BigDecimal exitPrice, LocalDate closed) {
        return single(symbol, qty, price, entry, exitPrice, closed, null);
    }

    private static BookPosition single(String symbol, BigDecimal qty, BigDecimal price,
                                       LocalDate entry, BigDecimal exitPrice, LocalDate closed,
                                       LocalDate fillDate) {
        return new BookPosition(1L, symbol, closed == null ? "OPEN" : "CLOSED", qty, price,
                entry, exitPrice, closed, qty, null, fillDate);
    }

    @Test
    void holdsFromTheEntryDayNotBefore() {
        var l = PositionLedger.build(
                List.of(single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D2, null, null)),
                Map.of("AAA", new BigDecimal("10")));

        assertThat(l.holdingsOn(D1)).isEmpty();
        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D3)).containsEntry("AAA", new BigDecimal("10"));
    }

    @Test
    void exitDayNoLongerHolds() {
        var l = PositionLedger.build(
                List.of(single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                        new BigDecimal("22.00"), D3)),
                Map.of());

        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D3)).isEmpty();
    }

    @Test
    void trancheTwoArrivesTheDayAfterEntry() {
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("30"), new BigDecimal("20.00"),
                D1, null, null, new BigDecimal("10"), null, null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("30")));

        assertThat(l.holdingsOn(D1)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("30"));
    }

    @Test
    void qtySyncDateOverridesTheDayAfterEntry() {
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("30"), new BigDecimal("20.00"),
                D1, null, null, new BigDecimal("10"), D5, null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("30")));

        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D3)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D5)).containsEntry("AAA", new BigDecimal("30"));
    }

    @Test
    void cancelledProducesNeitherHoldingNorCashMovement() {
        var p = new BookPosition(1L, "AAA", "CANCELLED", new BigDecimal("10"),
                new BigDecimal("20.00"), D1, null, D3, new BigDecimal("10"), null, null);

        var l = PositionLedger.build(List.of(p), Map.of());

        assertThat(l.holdingsOn(D2)).isEmpty();
        assertThat(l.events()).isEmpty();
    }

    @Test
    void bookOpenButAbsentAtBrokerIsDroppedEntirely() {
        // The PAYO case (spec §1.7): the book carries an open position the broker never
        // received. Kept, it would inflate cash across the whole reconstructed window,
        // because the backwards pass adds its purchase back for every earlier day.
        var p = single("BBB", new BigDecimal("100"), new BigDecimal("10.00"), D2, null, null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("10")));

        assertThat(l.holdingsOn(D3)).isEmpty();
        assertThat(l.events()).isEmpty();
        assertThat(l.excluded()).anySatisfy(s -> assertThat(s).contains("BBB"));
    }

    @Test
    void closedPositionsAreNotSubjectToTheBrokerCheck() {
        // A closed position is absent at the broker by definition. Dropping it would delete
        // exactly the realized losses the whole feature exists to show.
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                new BigDecimal("15.00"), D3);

        var l = PositionLedger.build(List.of(p), Map.of());

        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.excluded()).isEmpty();
    }

    @Test
    void eventsCarryTheBuyOutAndTheSaleIn() {
        // The service reads events() directly (converting each at its own date's FX rate and
        // bounding them at the anchor), so the events themselves are what the tests assert on.
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                new BigDecimal("22.00"), D3);

        var l = PositionLedger.build(List.of(p), Map.of());

        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(D1, "AAA", new BigDecimal("200.00")),
                new PositionLedger.CashEvent(D3, "AAA", new BigDecimal("-220.00")));
    }

    @Test
    void missingEnterQtyThrowsRatherThanGuessing() {
        // enterQty null means the ENTER row could not be matched (e.g. source_signal_id is
        // NULL). A guessed tranche split would be a wrong curve that looks right, so build()
        // fails loudly instead.
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("10"), new BigDecimal("20.00"),
                D1, null, null, null, null, null);

        assertThatThrownBy(() -> PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("10"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AAA");
    }

    @Test
    void enterQtyAboveHeldQuantityIsClampedToTheBook() {
        // A reconcile shortfall can leave qty BELOW the ordered quantity. Booking the full
        // ENTER quantity would inflate both the holding and the purchase cash beyond what was
        // ever actually held.
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("10"), new BigDecimal("20.00"),
                D1, null, null, new BigDecimal("20"), null, null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("10")));

        assertThat(l.holdingsOn(D1)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(D1, "AAA", new BigDecimal("200.00")));
        assertThat(l.excluded()).anySatisfy(s -> assertThat(s).contains("AAA"));
    }

    @Test
    void trancheTwoDatedAtOrAfterTheCloseIsDropped() {
        // If the QTY_SYNC lands on or after the close, the resulting holding would never
        // appear in holdingsOn() while the cash event still fired — a purchase the backwards
        // pass would subtract with no trace of it in the curve.
        var p = new BookPosition(1L, "AAA", "CLOSED", new BigDecimal("30"), new BigDecimal("20.00"),
                D1, new BigDecimal("22.00"), D3, new BigDecimal("10"), D3, null);

        var l = PositionLedger.build(List.of(p), Map.of());

        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D3)).isEmpty();
        // The exit must sell the 10 shares that were BOOKED, not the book row's 30: with
        // tranche 2 dropped, an exit of 30 * 22.00 would hand the reconstruction 660 of
        // proceeds for shares this ledger never says were held, and the backwards pass would
        // carry that 440 error across the whole span left of the close. Nothing in the seam
        // check would see it — the position closes before the anchor.
        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(D1, "AAA", new BigDecimal("200.00")),
                new PositionLedger.CashEvent(D3, "AAA", new BigDecimal("-220.00")));
        assertThat(l.excluded()).anySatisfy(s -> assertThat(s).contains("AAA"));
    }

    @Test
    void closedWithoutAnExitPriceIsRecordedNotSilentlyLost() {
        // No exit price means no sale-proceeds cash event can be derived. Silently omitting
        // it would make the curve lose the position's value with no trace of why.
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1, null, D3);

        var l = PositionLedger.build(List.of(p), Map.of());

        // "Excluded" must mean excluded: the buy and the holding go with the missing sale.
        // Keeping them would leave every day left of the close inflated by the sale proceeds
        // that were never booked — value appearing out of nothing, invisible to the seam
        // check because the position closes before the anchor.
        assertThat(l.events()).isEmpty();
        assertThat(l.holdings()).isEmpty();
        assertThat(l.holdingsOn(D2)).isEmpty();
        assertThat(l.excluded()).anySatisfy(s -> assertThat(s).contains("AAA"));
    }

    @Test
    void payoShapeHoldsFromTheCorroboratedFillDateNotTheBookEntry() {
        // The PAYO defect (design spec §1): book entry_date D1, but the broker's first SYNC
        // corroboration is D1+5. Tranche 1 must hold from D1+5, and its cash event must be
        // dated D1+5 too -- not D1, which would falsely add PAYO's holding (and subtract its
        // purchase cash) for five days it was never actually held.
        LocalDate fillDate = D1.plusDays(5);
        var p = single("PAYO", new BigDecimal("10"), new BigDecimal("20.00"), D1, null, null, fillDate);

        var l = PositionLedger.build(List.of(p), Map.of("PAYO", new BigDecimal("10")));

        assertThat(l.holdingsOn(D1)).isEmpty();
        assertThat(l.holdingsOn(fillDate)).containsEntry("PAYO", new BigDecimal("10"));
        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(fillDate, "PAYO", new BigDecimal("200.00")));
    }

    @Test
    void guardAFallsBackToEntryDateWhenFillDateIsNotBeforeTheClose() {
        // fillDate == closedAt is reachable (a SYNC at 10:00 the same day a position closes at
        // 19:55, both truncated to a date). Using it would clamp tranche 1 to nothing left of
        // the close, zero heldQty, zero the exit event, and delete a realized loss -- the
        // exact silent value loss PositionLedger's own class javadoc names as this feature's
        // reason to exist. Rule: the position is NOT excluded, only its dating falls back to
        // entryDate.
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                new BigDecimal("15.00"), D3, D3);

        var l = PositionLedger.build(List.of(p), Map.of());

        assertThat(l.excluded()).isEmpty();
        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        // The regression assertion for a deleted realized loss: the exit must still sell the
        // 10 shares actually held, non-zero.
        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(D1, "AAA", new BigDecimal("200.00")),
                new PositionLedger.CashEvent(D3, "AAA", new BigDecimal("-150.00")));
    }

    @Test
    void guardBPreventsTrancheTwoFromPrecedingTheCorroboratedTrancheOne() {
        // tranche 2's raw date (entryDate + 1, no qtySyncDate here) would be D1+1, but
        // fillDate corroborates tranche 1 only from D1+5 -- the PAYO shape (design spec §3.3).
        // Without the clamp, tranche 2 would be held, and its cash booked, on days tranche 1
        // does not exist yet.
        LocalDate fillDate = D1.plusDays(5);
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("30"), new BigDecimal("20.00"),
                D1, null, null, new BigDecimal("10"), null, fillDate);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("30")));

        assertThat(l.holdingsOn(D1.plusDays(1))).isEmpty();
        assertThat(l.holdingsOn(fillDate)).containsEntry("AAA", new BigDecimal("30"));
        assertThat(l.events()).containsExactly(
                new PositionLedger.CashEvent(fillDate, "AAA", new BigDecimal("200.00")),
                new PositionLedger.CashEvent(fillDate, "AAA", new BigDecimal("400.00")));
    }

    @Test
    void noFillDateFallsBackToEntryDateAndIsNamedUncorroborated() {
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1, null, null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("10")));

        assertThat(l.holdingsOn(D1)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.uncorroboratedPositions()).containsExactly("AAA");
        assertThat(l.lateCorroborations()).isEmpty();
    }

    @Test
    void lateCorroborationIsNamedButAnEarlyOneIsNot() {
        // ACA's real-world gap (design spec §2): 23 days between entryDate and fillDate. STT
        // is the negative control: fillDate one day after entryDate must NOT be flagged.
        var late = single("ACA", new BigDecimal("10"), new BigDecimal("20.00"), D1, null, null,
                D1.plusDays(23));
        var early = single("STT", new BigDecimal("10"), new BigDecimal("20.00"), D1, null, null,
                D1.plusDays(1));

        var l = PositionLedger.build(List.of(late, early),
                Map.of("ACA", new BigDecimal("10"), "STT", new BigDecimal("10")));

        assertThat(l.lateCorroborations()).hasSize(1)
                .anySatisfy(s -> assertThat(s).contains("ACA")
                        .contains(D1.toString())
                        .contains(D1.plusDays(23).toString()));
        assertThat(l.uncorroboratedPositions()).isEmpty();
    }

    @Test
    void lateCorroborationBoundaryIsStrictlyAfterTwoDays() {
        // The condition is isAfter(entryDate.plusDays(2)): exactly +2 must NOT be late (still
        // within the allowed window), and +3 must. Pins the boundary itself, not just a value
        // deep on either side of it -- an off-by-one to plusDays(1) or plusDays(3) would slip
        // past a test that only checked +1 (not late) and +23 (late).
        var exactlyTwoDays = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                null, null, D1.plusDays(2));
        var threeDays = single("BBB", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                null, null, D1.plusDays(3));

        var l = PositionLedger.build(List.of(exactlyTwoDays, threeDays),
                Map.of("AAA", new BigDecimal("10"), "BBB", new BigDecimal("10")));

        assertThat(l.lateCorroborations()).hasSize(1)
                .anySatisfy(s -> assertThat(s).contains("BBB"));
        assertThat(l.lateCorroborations()).noneMatch(s -> s.contains("AAA"));
    }
}
