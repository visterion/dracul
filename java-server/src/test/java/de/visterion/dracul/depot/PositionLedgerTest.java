package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        return new BookPosition(1L, symbol, closed == null ? "OPEN" : "CLOSED", qty, price,
                entry, exitPrice, closed, qty, null);
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
                D1, null, null, new BigDecimal("10"), null);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("30")));

        assertThat(l.holdingsOn(D1)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("30"));
    }

    @Test
    void qtySyncDateOverridesTheDayAfterEntry() {
        var p = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("30"), new BigDecimal("20.00"),
                D1, null, null, new BigDecimal("10"), D5);

        var l = PositionLedger.build(List.of(p), Map.of("AAA", new BigDecimal("30")));

        assertThat(l.holdingsOn(D2)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D3)).containsEntry("AAA", new BigDecimal("10"));
        assertThat(l.holdingsOn(D5)).containsEntry("AAA", new BigDecimal("30"));
    }

    @Test
    void cancelledProducesNeitherHoldingNorCashMovement() {
        var p = new BookPosition(1L, "AAA", "CANCELLED", new BigDecimal("10"),
                new BigDecimal("20.00"), D1, null, D3, new BigDecimal("10"), null);

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
    void netCashAfterAddsBuysAndSubtractsSells() {
        var p = single("AAA", new BigDecimal("10"), new BigDecimal("20.00"), D1,
                new BigDecimal("22.00"), D3);

        var l = PositionLedger.build(List.of(p), Map.of());

        // After D0: buy 200 flowed out, sell 220 flowed in -> net -20 to undo
        assertThat(l.netCashAfter(LocalDate.of(2026, 3, 1)))
                .isEqualByComparingTo(new BigDecimal("-20.00"));
        // After D1 the buy has already happened; only the sell is still ahead
        assertThat(l.netCashAfter(D1)).isEqualByComparingTo(new BigDecimal("-220.00"));
        // After the sell, nothing is left
        assertThat(l.netCashAfter(D3)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
