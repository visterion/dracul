package de.visterion.dracul.executor.broker;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit test — no Spring context. FakeExecutionGateway is the in-memory test double
 *  for {@link ExecutionGateway}, used by executor tests instead of a real broker adapter. */
class FakeExecutionGatewayTest {

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();

    @Test
    void flattenFull() {
        gateway.seedPosition(new BrokerPosition("ACME", "LONG", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("108")));

        CloseResult result = gateway.flatten("c", "ACME", new BigDecimal("1.0"));

        assertThat(result.closedQty()).isEqualByComparingTo("10");
        assertThat(result.remainingQty()).isEqualByComparingTo("0");
        assertThat(result.avgFillPrice()).isEqualByComparingTo("108");
        assertThat(gateway.positions("c")).extracting(BrokerPosition::symbol).doesNotContain("ACME");
        assertThat(gateway.flattenedSymbols).contains("ACME");
    }

    @Test
    void flattenPartial() {
        gateway.seedPosition(new BrokerPosition("ACME", "LONG", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("108")));

        CloseResult result = gateway.flatten("c", "ACME", new BigDecimal("0.5"));

        assertThat(result.closedQty()).isEqualByComparingTo("5");
        assertThat(result.remainingQty()).isEqualByComparingTo("5");
        assertThat(gateway.positions("c"))
                .filteredOn(p -> p.symbol().equals("ACME"))
                .extracting(BrokerPosition::qty)
                .first()
                .satisfies(qty -> assertThat((BigDecimal) qty).isEqualByComparingTo("5"));
    }

    @Test
    void placeBracketReturnsIds() {
        BracketRequest req = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), "ref-1", "DAY");

        PlacedBracket placedBracket = gateway.placeBracket("c", req);

        assertThat(placedBracket.bracketId()).isNotNull();
        assertThat(placedBracket.stopLegId()).isNotNull();
        assertThat(placedBracket.takeProfitLegId()).isNotNull();
        assertThat(gateway.placed).hasSize(1);
    }

    @Test
    void modifyRecorded() {
        ModifyResult result = gateway.modifyBracket("c", "stop-1", "ACME", new BigDecimal("104"), null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.newStop()).isEqualByComparingTo("104");
        assertThat(gateway.modifyCalls).hasSize(1);
    }

    @Test
    void orderByRefFound() {
        gateway.seedOrder(new BrokerOrder("ord-1", "r1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                new BigDecimal("10"), BigDecimal.ZERO, null, null));

        Optional<BrokerOrder> found = gateway.orderByRef("c", "r1");

        assertThat(found).isPresent();
        assertThat(found.get().orderId()).isEqualTo("ord-1");
    }

    @Test
    void orderByRefEmpty() {
        gateway.seedOrder(new BrokerOrder("ord-1", "r1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                new BigDecimal("10"), BigDecimal.ZERO, null, null));

        assertThat(gateway.orderByRef("c", "nope")).isEmpty();
    }

    @Test
    void unavailableThrows() {
        gateway.unavailable = true;

        assertThatThrownBy(() -> gateway.account("c")).isInstanceOf(BrokerUnavailableException.class);
    }
}
