package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** The broker port the executor depends on. Real adapter = AgoraExecutionGateway;
 *  test/mock = FakeExecutionGateway. Pure broker mechanics — no investment logic. */
public interface ExecutionGateway {
    AccountSnapshot account(String connection);
    List<BrokerPosition> positions(String connection);
    List<BrokerClosedPosition> closedPositions(String connection);
    /** Orders the broker currently reports as OPEN. A filled order is never in here — Saxo backs
     *  this with {@code /port/v1/orders/me} and Alpaca defaults {@code /v2/orders} to
     *  {@code status=open}. Use {@link #filledOrdersSince} to see fills. */
    List<BrokerOrder> orders(String connection);

    /**
     * Orders that reached a terminal state at or after {@code since} — the only way to observe a
     * FILLED order, since {@link #orders} is an open-orders view on every broker. Backed by
     * Agora's history path (Saxo {@code /cs/v1/audit/orderactivities}, Alpaca
     * {@code status=closed}), which carries real fills but no bracket-leg structure: expect a
     * missing {@code parentId} and a best-effort {@code role}, and match by order id.
     */
    List<BrokerOrder> filledOrdersSince(String connection, java.time.Instant since);
    Optional<BrokerOrder> orderByRef(String connection, String ref);
    PlacedBracket placeBracket(String connection, BracketRequest req);
    /** fraction in (0,1]; 1.0 = full close. */
    CloseResult flatten(String connection, String symbol, BigDecimal fraction);
    /** null stop/target = leave that leg unchanged. {@code symbol} is required by Agora's
     *  modify_bracket contract (parent-lookup + symbol-fallback leg resolution). */
    default ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target) {
        return modifyBracket(connection, orderId, symbol, stop, target, null, null);
    }

    /**
     * Leg-aware variant: {@code stopOrderId}/{@code targetOrderId} name the exact broker order
     * carrying each leg. Null (both) == the resolution Agora does on its own, which is only
     * unambiguous while the symbol carries ONE bracket. A position built in two tranches has two
     * protective stops working on the same instrument, and each one has to be addressed by name —
     * see {@code StopRatchetService} for what happens when they are not.
     */
    ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target,
            String stopOrderId, String targetOrderId);
    /** Cancels a still-working order (e.g. an unfilled GTD entry past expiry). Never re-prices —
     *  callers that want a different price must cancel then place a new order. */
    void cancelOrder(String connection, String orderId);
}
