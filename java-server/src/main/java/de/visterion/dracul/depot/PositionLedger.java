package de.visterion.dracul.depot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns book rows into "which shares were held on which day, and which cash moved when".
 *
 * <p>The only place that knows the tranche rule (spec §3.3) and the anchor-day reconciliation
 * against the broker (spec §3.4). Deliberately free of database and HTTP so both rules are
 * provable by plain unit tests.
 *
 * <p>Amounts stay in the position's own currency. Conversion is a later step's job, because
 * only it knows the per-day FX rate.
 *
 * <p><b>The invariant this class exists to keep:</b> a position's exit event sells exactly the
 * quantity that was booked into its holdings — never the raw book quantity. Every partial
 * exclusion below (a clamped tranche 1, a dropped tranche 2) reduces what is held, and an exit
 * event derived from {@code p.qty()} instead would invent sale proceeds for shares this ledger
 * never says were owned. {@link #assertExitSellsExactlyWhatWasHeld} pins that per position, so
 * the failure is a loud exception rather than a plausible-looking curve.
 */
public final class PositionLedger {

    /** {@code amount} positive = cash left the account (a buy); negative = cash arrived. */
    public record CashEvent(LocalDate date, String symbol, BigDecimal amount) {
    }

    /** {@code untilExclusive} null means "still held". */
    public record Holding(String symbol, BigDecimal qty, LocalDate from, LocalDate untilExclusive) {
    }

    /**
     * @param uncorroboratedPositions positions dated from {@code entryDate} because no SYNC
     *                                row exists for them at all — no broker corroboration
     * @param lateCorroborations      positions whose {@code fillDate} landed more than two
     *                                days after {@code entryDate}. A late corroboration is
     *                                indistinguishable from a late fill (design spec §2), so
     *                                the operator must see it even though the date was used.
     */
    public record Ledger(List<CashEvent> events, List<Holding> holdings, List<String> excluded,
                          List<String> uncorroboratedPositions, List<String> lateCorroborations) {

        public Map<String, BigDecimal> holdingsOn(LocalDate d) {
            Map<String, BigDecimal> out = new HashMap<>();
            for (Holding h : holdings) {
                if (h.from().isAfter(d)) continue;
                if (h.untilExclusive() != null && !h.untilExclusive().isAfter(d)) continue;
                out.merge(h.symbol(), h.qty(), BigDecimal::add);
            }
            return out;
        }
    }

    private PositionLedger() {
    }

    /**
     * @param book             every position of ONE connection
     * @param brokerQtyBySymbol the broker's holdings on the anchor day; used only to drop
     *                          book-open positions the broker never received
     */
    public static Ledger build(List<BookPosition> book, Map<String, BigDecimal> brokerQtyBySymbol) {
        List<CashEvent> events = new ArrayList<>();
        List<Holding> holdings = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<String> uncorroboratedPositions = new ArrayList<>();
        List<String> lateCorroborations = new ArrayList<>();

        for (BookPosition p : book) {
            if ("CANCELLED".equals(p.status())) {
                excluded.add(p.symbol() + ": CANCELLED, never filled");
                continue;
            }
            // The broker check applies to still-open positions only. A closed position is
            // absent at the broker by definition; dropping it would delete exactly the
            // realized losses this feature exists to surface.
            if ("OPEN".equals(p.status()) && !brokerQtyBySymbol.containsKey(p.symbol())) {
                excluded.add(p.symbol() + ": open in the book, absent at the broker");
                continue;
            }
            // No ENTER row means no source_signal_id match, so the tranche split is unknown.
            // Fail loudly: a guessed split is a wrong curve that looks right.
            if (p.enterQty() == null) {
                throw new IllegalArgumentException(
                        "no ENTER order for " + p.symbol() + " (position " + p.id()
                        + ") — the tranche split cannot be derived");
            }

            // A reconcile shortfall can leave qty BELOW the ordered quantity
            // (ReconcileService emits QTY_SYNC_SHORTFALL for exactly this). Booking the full
            // order quantity would inflate both the holding and the purchase cash.
            BigDecimal t1 = p.enterQty().min(p.qty());
            if (t1.compareTo(p.enterQty()) != 0) {
                excluded.add(p.symbol() + ": ENTER ordered " + p.enterQty()
                        + " but only " + p.qty() + " held — tranche 1 clamped to the book");
            }
            BigDecimal t2 = p.qty().subtract(t1);

            // Tranche 1 dating (design spec §3.2): the corroborated fillDate, not the book's
            // entryDate, is the day the position was actually held -- unless it is null, in
            // which case there is nothing to corroborate with and entryDate is all there is.
            LocalDate tranche1Start = p.entryDate();
            if (p.fillDate() == null) {
                uncorroboratedPositions.add(p.symbol());
            } else {
                // Guard A -- a closed position never loses its exit (design spec §3.3, and
                // PositionLedger's own class javadoc "the invariant this class exists to
                // keep"). If fillDate lands on or after the close, using it would clamp
                // tranche 1 to nothing left of the close, zero heldQty, zero the exit event,
                // and delete a realized loss -- the exact silent value loss this class exists
                // to end. A proxy date that contradicts a hard book fact (the position DID
                // close, and DID sell something) yields to that fact; it does not erase it.
                // The position is NOT excluded -- only its dating falls back to entryDate.
                if (p.closedAt() == null || p.fillDate().isBefore(p.closedAt())) {
                    tranche1Start = p.fillDate();
                }
                if (p.fillDate().isAfter(p.entryDate().plusDays(2))) {
                    lateCorroborations.add(p.symbol() + ": entryDate " + p.entryDate()
                            + ", fillDate " + p.fillDate());
                }
            }

            LocalDate t2Date = p.qtySyncDate() != null ? p.qtySyncDate() : p.entryDate().plusDays(1);
            // Guard B -- tranche 2 can never precede tranche 1 (design spec §3.3). Neither
            // qtySyncDate nor the entryDate+1 fallback know about the corroborated
            // tranche1Start; without this, tranche 2 could be held on days tranche 1 does not
            // exist yet and its purchase cash booked before tranche 1's own. Applied BEFORE
            // the close-drop check just below so the CLAMPED date is the one that check tests.
            if (t2Date.isBefore(tranche1Start)) {
                t2Date = tranche1Start;
            }

            // Built per position and only merged into the ledger once the position survives
            // every exclusion below. An exclusion that left half of these behind is exactly
            // the silent value loss this feature was written to end.
            List<Holding> posHoldings = new ArrayList<>();
            List<CashEvent> posEvents = new ArrayList<>();

            posHoldings.add(new Holding(p.symbol(), t1, tranche1Start, p.closedAt()));
            posEvents.add(new CashEvent(tranche1Start, p.symbol(), t1.multiply(p.entryPrice())));

            if (t2.signum() > 0) {
                if (p.closedAt() != null && !t2Date.isBefore(p.closedAt())) {
                    // Holding would be empty for every day while the cash event still fires —
                    // the backwards pass would subtract a purchase that appears nowhere.
                    excluded.add(p.symbol() + ": tranche 2 dated " + t2Date
                            + " is not before the close " + p.closedAt() + " — dropped");
                } else {
                    posHoldings.add(new Holding(p.symbol(), t2, t2Date, p.closedAt()));
                    posEvents.add(new CashEvent(t2Date, p.symbol(), t2.multiply(p.entryPrice())));
                }
            }

            if (p.closedAt() != null) {
                if (p.exitPrice() == null) {
                    // A REAL exclusion: holdings and buy event go too. Keeping them while no
                    // sale event can be derived would inflate every day left of the close by
                    // the missing proceeds — the "+6.4 % while the broker reported a loss"
                    // failure this feature replaces, rebuilt inside the replacement. Excluding
                    // rather than aborting the whole run is deliberate: one unpriced close is
                    // a named gap in the report, and a gap is honest; aborting would leave the
                    // operator with no curve at all for a single bad row.
                    excluded.add(p.symbol() + ": closed on " + p.closedAt()
                            + " with no exit price — sale proceeds unknown, position excluded"
                            + " entirely (holdings and purchase dropped with it)");
                    continue;
                }
                // The exit sells what was BOOKED, not p.qty(): a clamped tranche 1 or a
                // dropped tranche 2 means fewer shares are held than the book row claims.
                BigDecimal heldQty = BigDecimal.ZERO;
                for (Holding h : posHoldings) heldQty = heldQty.add(h.qty());
                posEvents.add(new CashEvent(p.closedAt(), p.symbol(),
                        heldQty.multiply(p.exitPrice()).negate()));
            }

            assertExitSellsExactlyWhatWasHeld(p, posHoldings, posEvents);
            holdings.addAll(posHoldings);
            events.addAll(posEvents);
        }
        return new Ledger(List.copyOf(events), List.copyOf(holdings), List.copyOf(excluded),
                List.copyOf(uncorroboratedPositions), List.copyOf(lateCorroborations));
    }

    /**
     * The ledger's core invariant, checked per position: the quantity summed over the
     * position's holdings is exactly the quantity its exit event sells. Re-derived from the
     * two built lists rather than from the running totals above, so that a future edit which
     * sells {@code p.qty()} again — or which drops a holding but keeps its exit — trips here
     * instead of shipping a curve that is wrong by the difference.
     *
     * <p>Checked against the event AMOUNT ({@code heldQty * exitPrice}), which is the same
     * statement as long as the exit price is non-zero; a zero exit price makes the sale
     * proceeds zero either way and there is nothing left to distinguish.
     */
    private static void assertExitSellsExactlyWhatWasHeld(BookPosition p, List<Holding> posHoldings,
                                                          List<CashEvent> posEvents) {
        if (p.exitPrice() == null) return;   // no sale event can exist without a price
        BigDecimal heldQty = BigDecimal.ZERO;
        for (Holding h : posHoldings) heldQty = heldQty.add(h.qty());
        for (CashEvent e : posEvents) {
            if (e.amount().signum() >= 0) continue;   // a purchase, not the sale
            BigDecimal expected = heldQty.multiply(p.exitPrice()).negate();
            if (e.amount().compareTo(expected) != 0) {
                throw new IllegalStateException(
                        "ledger invariant violated for " + p.symbol() + " (position " + p.id()
                        + "): holdings total " + heldQty + " shares, so the exit at "
                        + p.exitPrice() + " must be " + expected + " but is " + e.amount()
                        + " — an exclusion left the position half in the ledger");
            }
        }
    }
}
