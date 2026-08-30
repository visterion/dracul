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

            BigDecimal t1 = p.enterQty();
            BigDecimal t2 = p.qty().subtract(t1);
            LocalDate t2Date = p.qtySyncDate() != null ? p.qtySyncDate() : p.entryDate().plusDays(1);

            holdings.add(new Holding(p.symbol(), t1, p.entryDate(), p.closedAt()));
            events.add(new CashEvent(p.entryDate(), p.symbol(), t1.multiply(p.entryPrice())));

            if (t2.signum() > 0) {
                holdings.add(new Holding(p.symbol(), t2, t2Date, p.closedAt()));
                events.add(new CashEvent(t2Date, p.symbol(), t2.multiply(p.entryPrice())));
            }

            if (p.closedAt() != null && p.exitPrice() != null) {
                events.add(new CashEvent(p.closedAt(), p.symbol(),
                        p.qty().multiply(p.exitPrice()).negate()));
            }
        }
        return new Ledger(List.copyOf(events), List.copyOf(holdings), List.copyOf(excluded));
    }
}
