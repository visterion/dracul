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
                new BigDecimal("100"), new BigDecimal("108"), null));

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
                new BigDecimal("100"), new BigDecimal("108"), null));

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

    /** ordersByRef's history half is, on the real gateway, a second filledOrdersSince call — the
     *  fake must fail the same way when that call would fail, or a test driving this failure path
     *  (Task 5a) would pass against the fake and throw against the real adapter. */
    @Test
    void ordersByRef_throwsWhenFilledOrderHistoryIsUnavailable() {
        gateway.seedOrder(new BrokerOrder("ord-1", "r1", "ACME", OrderRole.ENTRY, OrderStatus.WORKING,
                new BigDecimal("10"), BigDecimal.ZERO, null, null));
        gateway.filledOrdersUnavailable = true;

        assertThatThrownBy(() -> gateway.ordersByRef("c", "r1"))
                .isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    void placeProtectiveStopRecordsAndShowsALiveOpenStop() {
        String id = gateway.placeProtectiveStop("c", "SYNTH", new BigDecimal("15"), new BigDecimal("68.76"));

        assertThat(gateway.protectiveStops).containsExactly(new FakeExecutionGateway.ProtectiveStopCall(
                "SYNTH", new BigDecimal("15"), new BigDecimal("68.76")));
        BrokerOrder live = gateway.orders("c").stream().filter(o -> id.equals(o.orderId()))
                .findFirst().orElseThrow();
        assertThat(live.status()).isEqualTo(OrderStatus.WORKING);
        assertThat(live.rawStatus()).isEqualTo("working");
        assertThat(live.side()).isEqualTo("sell");
        assertThat(live.type()).isEqualTo("stopiftraded");
        assertThat(live.stopPrice()).isEqualByComparingTo("68.76");
        assertThat(live.qty()).isEqualByComparingTo("15");
    }

    @Test
    void protectiveStopFailuresAreConsumedOnePerCall() {
        gateway.protectiveStopFailures.add(new BrokerRejectedException("band", "PRICE_OUT_OF_BAND", java.util.List.of()));
        assertThatThrownBy(() -> gateway.placeProtectiveStop("c", "SYNTH", BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(BrokerRejectedException.class);
        assertThat(gateway.placeProtectiveStop("c", "SYNTH", BigDecimal.ONE, BigDecimal.TEN)).startsWith("pstop-");
        assertThat(gateway.protectiveStops).hasSize(2);
    }

    @Test
    void cancelRemovesTheOrderOnlyWhenAsked() {
        gateway.seedOrder(new BrokerOrder("stop-1", null, "SYNTH", OrderRole.STOP_LOSS,
                OrderStatus.WORKING, BigDecimal.TEN, null, null, null));
        gateway.cancelOrder("c", "stop-1");
        assertThat(gateway.orders("c")).extracting(BrokerOrder::orderId).contains("stop-1");

        gateway.cancelRemovesOrder = true;
        gateway.cancelOrder("c", "stop-1");
        assertThat(gateway.orders("c")).extracting(BrokerOrder::orderId).doesNotContain("stop-1");
        assertThat(gateway.cancelledOrderIds).containsExactly("stop-1", "stop-1");
    }

    @Test
    void failCancelForOneOrderIdOnly() {
        gateway.failCancelForOrderId = "stop-2";
        gateway.cancelOrder("c", "stop-1");
        assertThatThrownBy(() -> gateway.cancelOrder("c", "stop-2"))
                .isInstanceOf(BrokerUnavailableException.class);
        assertThat(gateway.cancelledOrderIds).containsExactly("stop-1");
    }

    @Test
    void rejectPlaceBracketIsOneShot() {
        gateway.rejectPlaceBracketWith = new BrokerRejectedException("no", "INSUFFICIENT_FUNDS", java.util.List.of());
        BracketRequest req = new BracketRequest("SYNTH", "BUY", BigDecimal.ONE, BigDecimal.TEN,
                new BigDecimal("8"), null, "sp-1-202611", "gtc");
        assertThatThrownBy(() -> gateway.placeBracket("c", req)).isInstanceOf(BrokerRejectedException.class);
        assertThat(gateway.placeBracket("c", req).bracketId()).startsWith("brk-");
        assertThat(gateway.placed).hasSize(2);
    }
}
