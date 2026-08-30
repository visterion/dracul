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
 */
public final class PositionLedger {

    /** {@code amount} positive = cash left the account (a buy); negative = cash arrived. */
    public record CashEvent(LocalDate date, String symbol, BigDecimal amount) {
    }

    /** {@code untilExclusive} null means "still held". */
    public record Holding(String symbol, BigDecimal qty, LocalDate from, LocalDate untilExclusive) {
    }

    public record Ledger(List<CashEvent> events, List<Holding> holdings, List<String> excluded) {

        public Map<String, BigDecimal> holdingsOn(LocalDate d) {
            Map<String, BigDecimal> out = new HashMap<>();
            for (Holding h : holdings) {
                if (h.from().isAfter(d)) continue;
                if (h.untilExclusive() != null && !h.untilExclusive().isAfter(d)) continue;
                out.merge(h.symbol(), h.qty(), BigDecimal::add);
            }
            return out;
        }

        /**
         * Net cash movement strictly after {@code d}. The backwards pass adds this to the
         * anchor's cash: a buy that happened later must be given back, a sale taken away.
         */
        public BigDecimal netCashAfter(LocalDate d) {
            BigDecimal sum = BigDecimal.ZERO;
            for (CashEvent e : events) {
                if (e.date().isAfter(d)) sum = sum.add(e.amount());
            }
            return sum;
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
            LocalDate t2Date = p.qtySyncDate() != null ? p.qtySyncDate() : p.entryDate().plusDays(1);

            holdings.add(new Holding(p.symbol(), t1, p.entryDate(), p.closedAt()));
            events.add(new CashEvent(p.entryDate(), p.symbol(), t1.multiply(p.entryPrice())));

            if (t2.signum() > 0) {
                if (p.closedAt() != null && !t2Date.isBefore(p.closedAt())) {
                    // Holding would be empty for every day while the cash event still fires —
                    // the backwards pass would subtract a purchase that appears nowhere.
                    excluded.add(p.symbol() + ": tranche 2 dated " + t2Date
                            + " is not before the close " + p.closedAt() + " — dropped");
                } else {
                    holdings.add(new Holding(p.symbol(), t2, t2Date, p.closedAt()));
                    events.add(new CashEvent(t2Date, p.symbol(), t2.multiply(p.entryPrice())));
                }
            }

            if (p.closedAt() != null) {
                if (p.exitPrice() == null) {
                    // Without an exit price the holdings end but no sale cash arrives, so the
                    // curve would simply lose the position's value with no trace.
                    excluded.add(p.symbol() + ": closed on " + p.closedAt()
                            + " with no exit price — sale proceeds unknown");
                } else {
                    events.add(new CashEvent(p.closedAt(), p.symbol(),
                            p.qty().multiply(p.exitPrice()).negate()));
                }
            }
        }
        return new Ledger(List.copyOf(events), List.copyOf(holdings), List.copyOf(excluded));
    }
}
