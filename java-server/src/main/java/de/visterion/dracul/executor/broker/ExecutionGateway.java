package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** The broker port the executor depends on. Real adapter = AgoraExecutionGateway;
 *  test/mock = FakeExecutionGateway. Pure broker mechanics — no investment logic. */
public interface ExecutionGateway {
    AccountSnapshot account(String connection);
    List<BrokerPosition> positions(String connection);
    List<BrokerOrder> orders(String connection);
    Optional<BrokerOrder> orderByRef(String connection, String ref);
    PlacedBracket placeBracket(String connection, BracketRequest req);
    /** fraction in (0,1]; 1.0 = full close. */
    CloseResult flatten(String connection, String symbol, BigDecimal fraction);
    /** null stop/target = leave that leg unchanged. {@code symbol} is required by Agora's
     *  modify_bracket contract (parent-lookup + symbol-fallback leg resolution). */
    ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target);
}
