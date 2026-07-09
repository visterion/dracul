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

    public record ModifyCall(String orderId, String symbol, BigDecimal stop, BigDecimal target) {
    }

    private final Map<String, BrokerPosition> positionsBySymbol = new LinkedHashMap<>();
    private final List<BrokerOrder> orders = new ArrayList<>();
    private AccountSnapshot account = new AccountSnapshot(
            new BigDecimal("100000"), new BigDecimal("100000"), "USD");

    private final AtomicInteger counter = new AtomicInteger();

    public final List<String> flattenedSymbols = new ArrayList<>();
    public final List<BigDecimal> flattenFractions = new ArrayList<>();
    public final List<ModifyCall> modifyCalls = new ArrayList<>();
    public final List<BracketRequest> placed = new ArrayList<>();

    public boolean unavailable = false;

    public void seedPosition(BrokerPosition position) {
        positionsBySymbol.put(position.symbol(), position);
    }

    public void seedOrder(BrokerOrder order) {
        orders.add(order);
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
    public List<BrokerOrder> orders(String connection) {
        checkAvailable();
        return new ArrayList<>(orders);
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
        int n = counter.incrementAndGet();

        BrokerPosition position = positionsBySymbol.get(symbol);
        if (position == null) {
            return new CloseResult(BigDecimal.ZERO, BigDecimal.ZERO, null, "close-" + n);
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
                    position.avgEntryPrice(), position.marketPrice()));
        }

        return new CloseResult(closedQty, remainingQty, position.marketPrice(), "close-" + n);
    }

    @Override
    public ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target) {
        checkAvailable();
        modifyCalls.add(new ModifyCall(orderId, symbol, stop, target));
        return new ModifyResult(orderId, stop, target, true);
    }
}
