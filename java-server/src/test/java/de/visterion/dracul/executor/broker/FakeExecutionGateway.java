package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory, scriptable {@link ExecutionGateway} for tests. Not thread-safe by design —
 *  tests drive it single-threaded. */
public class FakeExecutionGateway implements ExecutionGateway {

    public record ModifyCall(String orderId, String symbol, BigDecimal stop, BigDecimal target,
            String stopOrderId, String targetOrderId) {
    }

    private final Map<String, BrokerPosition> positionsBySymbol = new LinkedHashMap<>();
    private final List<BrokerOrder> orders = new ArrayList<>();
    private final List<BrokerClosedPosition> closedPositions = new ArrayList<>();
    private AccountSnapshot account = new AccountSnapshot(
            new BigDecimal("100000"), new BigDecimal("100000"), "USD");

    private final AtomicInteger counter = new AtomicInteger();

    public final List<String> flattenedSymbols = new ArrayList<>();
    public final List<BigDecimal> flattenFractions = new ArrayList<>();
    public final List<ModifyCall> modifyCalls = new ArrayList<>();
    public final List<BracketRequest> placed = new ArrayList<>();
    public final List<String> cancelledOrderIds = new ArrayList<>();

    public boolean unavailable = false;

    /** Every {@code since} argument {@link #filledOrdersSince} was called with. */
    public final List<java.time.Instant> filledOrdersSinceArgs = new ArrayList<>();
    /** When true, only the filled-order history call fails — lets a test drive the fail-soft
     *  degradation to position-gone detection without taking the whole broker down. */
    public boolean filledOrdersUnavailable = false;
    /** When set, {@link #filledOrdersSince} throws exactly this exception instead of returning
     *  fills. Distinct from {@link #filledOrdersUnavailable} so a test can pick an arbitrary
     *  {@link RuntimeException} (not just {@link BrokerUnavailableException}) to prove the
     *  missing-evidence handling reacts to the call failing at all, not to one specific type. */
    public RuntimeException filledOrdersThrows = null;

    /** When &gt; 0, that many upcoming {@link #modifyBracket} calls fail with
     *  {@link #modifyFailureMessage} (one per call). The attempt is still recorded in
     *  {@link #modifyCalls}, so a test can count retries. */
    public int modifyFailures = 0;
    public String modifyFailureMessage = "fake modify failure";
    /** Optional cause attached to the injected {@link BrokerUnavailableException}. Real 429s
     *  reach {@code isTransient} this way — {@code AgoraExecutionGateway.call} wraps the
     *  underlying {@code HttpClientErrorException$TooManyRequests} rather than describing it,
     *  so the status lives in the CAUSE and never in the top-level message. */
    public Throwable modifyFailureCause = null;
    /** When set, an injected modify failure is a BUSINESS rejection carrying this reject code —
     *  {@link BrokerRejectedException}, the shape {@code AgoraExecutionGateway.requireAccepted}
     *  produces for {@code accepted:false}. Null = a plain {@link BrokerUnavailableException}, the
     *  shape a transport failure or an {@code available:false} tool takes. The distinction is not
     *  cosmetic: it is the only thing that tells an outage apart from a verdict the broker gave. */
    public String modifyRejectCode = null;
    /** When set, only calls naming THIS stop leg consume a {@link #modifyFailures} budget — lets a
     *  test fail exactly one leg of a two-leg (two-tranche) ratchet. Null = any call may fail. */
    public String failModifyForStopOrderId = null;

    /** When set, the next {@link #flatten} call throws this instead of closing the position, and
     *  is then cleared — one shot, so a test does not have to reset it after asserting the
     *  escalation. Lets a test hand in a real {@link BrokerRejectedException} carrying a typed
     *  reject code (e.g. {@code "NO_POSITION"}), the same shape
     *  {@code AgoraExecutionGateway.requireAccepted} produces for an {@code accepted:false}
     *  flatten, so the caller can be shown to branch on the TYPE, never on matching text in the
     *  message. */
    public RuntimeException rejectFlattenWith = null;

    public void seedPosition(BrokerPosition position) {
        positionsBySymbol.put(position.symbol(), position);
    }

    public void seedOrder(BrokerOrder order) {
        orders.add(order);
    }

    public void seedClosedPosition(BrokerClosedPosition closedPosition) {
        closedPositions.add(closedPosition);
    }

    public void setAccount(AccountSnapshot account) {
        this.account = account;
    }

    private void checkAvailable() {
        if (unavailable) {
            throw new BrokerUnavailableException("fake unavailable");
        }
    }

    @Override
    public AccountSnapshot account(String connection) {
        checkAvailable();
        return account;
    }

    @Override
    public List<BrokerPosition> positions(String connection) {
        checkAvailable();
        return new ArrayList<>(positionsBySymbol.values());
    }

    @Override
    public List<BrokerClosedPosition> closedPositions(String connection) {
        checkAvailable();
        return new ArrayList<>(closedPositions);
    }

    @Override
    public List<BrokerOrder> orders(String connection) {
        checkAvailable();
        // Mirrors the real gateway: this is an OPEN-orders view (Saxo /port/v1/orders/me, Alpaca's
        // status=open default), so a FILLED order is never in it. Seeded fills surface only via
        // filledOrdersSince — a fake that returned them here made unreachable production code look
        // exercised (BUG-S12).
        return orders.stream().filter(o -> o.status() != OrderStatus.FILLED)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Every seeded FILLED order. {@code since} is ignored — the fake keeps no timestamps; tests
     *  that care about the window assert on the argument via {@link #filledOrdersSinceArgs}. */
    @Override
    public List<BrokerOrder> filledOrdersSince(String connection, java.time.Instant since) {
        checkAvailable();
        filledOrdersSinceArgs.add(since);
        if (filledOrdersThrows != null) {
            throw filledOrdersThrows;
        }
        if (filledOrdersUnavailable) {
            throw new BrokerUnavailableException("fake filled-order history unavailable");
        }
        return orders.stream().filter(o -> o.status() == OrderStatus.FILLED)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Optional<BrokerOrder> orderByRef(String connection, String ref) {
        checkAvailable();
        return orders.stream()
                .filter(o -> o.orderId().equals(ref) || o.clientRef().equals(ref))
                .findFirst();
    }

    @Override
    public PlacedBracket placeBracket(String connection, BracketRequest req) {
        checkAvailable();
        placed.add(req);
        int n = counter.incrementAndGet();
        return new PlacedBracket("brk-" + n, "stop-" + n, "tp-" + n, req.clientRef(), OrderStatus.WORKING);
    }

    @Override
    public CloseResult flatten(String connection, String symbol, BigDecimal fraction) {
        checkAvailable();
        flattenedSymbols.add(symbol);
        flattenFractions.add(fraction);
        if (rejectFlattenWith != null) {
            RuntimeException toThrow = rejectFlattenWith;
            rejectFlattenWith = null;
            throw toThrow;
        }
        int n = counter.incrementAndGet();

        BrokerPosition position = positionsBySymbol.get(symbol);
        if (position == null) {
            return new CloseResult(BigDecimal.ZERO, BigDecimal.ZERO, null, "close-" + n, List.of(), false);
        }

        BigDecimal closedQty = position.qty().multiply(fraction);
        BigDecimal remainingQty;
        if (fraction.compareTo(BigDecimal.ONE) >= 0) {
            positionsBySymbol.remove(symbol);
            remainingQty = BigDecimal.ZERO;
        } else {
            remainingQty = position.qty().subtract(closedQty);
            positionsBySymbol.put(symbol, new BrokerPosition(
                    position.symbol(), position.side(), remainingQty,
                    position.avgEntryPrice(), position.marketPrice(), position.openOrdersCount()));
        }

        return new CloseResult(closedQty, remainingQty, position.marketPrice(), "close-" + n, List.of(), false);
    }

    @Override
    public ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target,
            String stopOrderId, String targetOrderId) {
        checkAvailable();
        modifyCalls.add(new ModifyCall(orderId, symbol, stop, target, stopOrderId, targetOrderId));
        boolean legSelected = failModifyForStopOrderId == null
                || failModifyForStopOrderId.equals(stopOrderId);
        if (modifyFailures > 0 && legSelected) {
            modifyFailures--;
            if (modifyRejectCode != null) {
                throw new BrokerRejectedException(modifyFailureMessage, modifyRejectCode, List.of());
            }
            throw modifyFailureCause == null
                    ? new BrokerUnavailableException(modifyFailureMessage)
                    : new BrokerUnavailableException(modifyFailureMessage, modifyFailureCause);
        }
        return new ModifyResult(orderId, stop, target, true);
    }

    @Override
    public void cancelOrder(String connection, String orderId) {
        checkAvailable();
        cancelledOrderIds.add(orderId);
    }
}
