package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Synthetic Sparplan fixtures: CONVICTION positions, their single leg, broker orders, levels. */
final class SavingsFixtures {

    static final String CONN = "c";

    private SavingsFixtures() {
    }

    /** Builder for an OPEN, filled, single-leg CONVICTION position (stop = entry × 0.65). */
    static final class P {
        final long id; final String symbol;
        String qty = "10"; String entry = "100"; String activeStop; int trimCount; String pendingTrim;
        String pendingExit; String catastrophe; boolean filled = true; String stopOrderId; String status = "OPEN";

        P(long id, String symbol) { this.id = id; this.symbol = symbol; this.stopOrderId = "stop-" + id; }
        P qty(String v) { qty = v; return this; }
        P entry(String v) { entry = v; return this; }
        P activeStop(String v) { activeStop = v; return this; }
        P trimCount(int v) { trimCount = v; return this; }
        P pendingTrim(String v) { pendingTrim = v; return this; }
        P pendingExit(String v) { pendingExit = v; return this; }
        P catastrophe(String v) { catastrophe = v; return this; }
        P unfilled() { filled = false; return this; }
        P stop(String v) { stopOrderId = v; return this; }
        P status(String v) { status = v; return this; }

        ExecutorPosition build() {
            BigDecimal e = new BigDecimal(entry);
            BigDecimal initial = ConvictionProfile.defaults().emergencyStop("BUY", e);
            BigDecimal active = activeStop == null ? initial : new BigDecimal(activeStop);
            ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(id, CONN, symbol, "BUY",
                    new BigDecimal(qty), e, initial, active, 1, null, List.of(), "sig-" + id,
                    "strigoi-tech", "2026-10-01", null, status, "brk-" + id, e, null, 0, null, null,
                    null, null, stopOrderId, null, null, null, null, trimCount, null, null, null,
                    pendingExit, pendingExit == null ? null : "close-" + id, null, false, active,
                    filled ? "2026-10-01T14:30:00Z" : null);
            return ExecutorPositionFixtures.withProfileFields(base, ExitProfile.CONVICTION, catastrophe,
                    catastrophe == null ? null : "2026-11-02 22:30:00+00", pendingTrim, false);
        }
    }

    static P pos(long id, String symbol) {
        return new P(id, symbol);
    }

    static ExecutorPositionLeg leg(ExecutorPosition p) {
        return new ExecutorPositionLeg(p.id() * 10, p.id(), 1, "brk-" + p.id(), p.stopOrderId(),
                p.qty(), ExecutorPositionLeg.OPEN, null, null, null);
    }

    static ExecutorIndicators.Levels levels(String close) {
        return new ExecutorIndicators.Levels(true, new BigDecimal("2"), null,
                close == null ? null : new BigDecimal(close), null);
    }

    static BrokerOrder liveStop(String id, String symbol, String qty, String stop) {
        return new BrokerOrder(id, null, symbol, OrderRole.STOP_LOSS, OrderStatus.WORKING,
                new BigDecimal(qty), null, null, null, "sell", "stopiftraded", "working", "open",
                null, new BigDecimal(stop), null);
    }

    static BrokerOrder liveParent(String id, String ref, String symbol, String qty, String limit) {
        return new BrokerOrder(id, ref, symbol, OrderRole.OTHER, OrderStatus.WORKING,
                new BigDecimal(qty), null, null, null, "buy", "limit", "working", "open",
                new BigDecimal(limit), null, null);
    }

    static BrokerOrder filledParent(String id, String ref, String symbol, String qty, String price) {
        return new BrokerOrder(id, ref, symbol, OrderRole.OTHER, OrderStatus.FILLED,
                new BigDecimal(qty), new BigDecimal(qty), new BigDecimal(price), null, "buy", "limit",
                "finalfill", "history", null, null, Instant.parse("2026-11-03T14:30:00Z"));
    }

    static BrokerOrder filledStop(String id, String symbol, String qty, String price) {
        return new BrokerOrder(id, null, symbol, OrderRole.STOP_LOSS, OrderStatus.FILLED,
                new BigDecimal(qty), new BigDecimal(qty), new BigDecimal(price), null, "sell",
                "stopiftraded", "finalfill", "history", null, null, Instant.parse("2026-11-03T18:00:00Z"));
    }
}
