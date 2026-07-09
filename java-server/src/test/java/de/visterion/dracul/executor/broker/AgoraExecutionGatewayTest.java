package de.visterion.dracul.executor.broker;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgoraExecutionGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    /** Capturing subclass: stubs the HTTP seam, records the last (tool, args) call. */
    private static class CapturingGateway extends AgoraExecutionGateway {
        String capturedTool;
        JsonNode capturedArgs;
        JsonNode canned;

        CapturingGateway(ObjectMapper mapper) {
            super("http://x", "tkn", mapper);
        }

        @Override
        protected JsonNode call(String tool, JsonNode args) {
            this.capturedTool = tool;
            this.capturedArgs = args;
            return canned;
        }
    }

    @Test void accountBuildsArgsAndMaps() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"cash\":\"1000\",\"buyingPower\":\"2000\",\"currency\":\"USD\"}}");

        AccountSnapshot result = gw.account("saxo-sim");

        assertThat(gw.capturedTool).isEqualTo("get_account");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("saxo-sim");
        assertThat(result.cash()).isEqualByComparingTo("1000");
        assertThat(result.buyingPower()).isEqualByComparingTo("2000");
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test void positionsMapsArray() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"positions":[
                    {"symbol":"ACME","side":"LONG","qty":"10","avgEntryPrice":"100","marketPrice":"108"}
                ]}}
                """);

        List<BrokerPosition> result = gw.positions("saxo-sim");

        assertThat(gw.capturedTool).isEqualTo("get_positions");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("saxo-sim");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("ACME");
        assertThat(result.get(0).qty()).isEqualByComparingTo("10");
        assertThat(result.get(0).marketPrice()).isEqualByComparingTo("108");
    }

    @Test void ordersMapsRoleAndStatusAndFillInfo() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"orderId":"ord-1","clientRef":"ref-1","symbol":"ACME","role":"stop_loss",
                     "status":"partially_filled","qty":"10","filledQty":"4","avgFillPrice":"95",
                     "parentId":"brk-1"}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("saxo-sim");

        assertThat(gw.capturedTool).isEqualTo("get_orders");
        assertThat(result).hasSize(1);
        BrokerOrder order = result.get(0);
        assertThat(order.orderId()).isEqualTo("ord-1");
        assertThat(order.role()).isEqualTo(OrderRole.STOP_LOSS);
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.filledQty()).isEqualByComparingTo("4");
        assertThat(order.avgFillPrice()).isEqualByComparingTo("95");
        assertThat(order.parentId()).isEqualTo("brk-1");
    }

    @Test void orderByRefFound() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"order":{"orderId":"ord-1","clientRef":"ref-1","symbol":"ACME",
                    "role":"entry","status":"working","qty":"10","filledQty":"0"}}}
                """);

        Optional<BrokerOrder> result = gw.orderByRef("saxo-sim", "ref-1");

        assertThat(gw.capturedTool).isEqualTo("get_order_by_ref");
        assertThat(gw.capturedArgs.path("ref").asString()).isEqualTo("ref-1");
        assertThat(result).isPresent();
        assertThat(result.get().orderId()).isEqualTo("ord-1");
        assertThat(result.get().role()).isEqualTo(OrderRole.ENTRY);
    }

    @Test void orderByRefEmptyWhenMissing() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{}}");

        Optional<BrokerOrder> result = gw.orderByRef("saxo-sim", "nope");

        assertThat(result).isEmpty();
    }

    @Test void placeBracketBuildsArgsAndMapsIds() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"bracketId":"brk-1","stopLegId":"stop-1","takeProfitLegId":"tp-1",
                    "clientRef":"sig-1","status":"working"}}
                """);

        BracketRequest req = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), "sig-1", "DAY");

        PlacedBracket result = gw.placeBracket("saxo-sim", req);

        assertThat(gw.capturedTool).isEqualTo("place_bracket");
        JsonNode args = gw.capturedArgs;
        assertThat(args.path("connection").asString()).isEqualTo("saxo-sim");
        assertThat(args.path("symbol").asString()).isEqualTo("ACME");
        assertThat(args.path("side").asString()).isEqualTo("BUY");
        assertThat(args.has("qty")).isTrue();
        assertThat(args.has("stop_loss_stop")).isTrue();
        assertThat(args.has("take_profit_limit")).isTrue();
        assertThat(args.path("client_ref").asString()).isEqualTo("sig-1");

        assertThat(result.bracketId()).isEqualTo("brk-1");
        assertThat(result.stopLegId()).isEqualTo("stop-1");
        assertThat(result.takeProfitLegId()).isEqualTo("tp-1");
        assertThat(result.status()).isEqualTo(OrderStatus.WORKING);
    }

    @Test void placeBracketOmitsOptionalArgs() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"bracketId":"brk-1","stopLegId":"stop-1","takeProfitLegId":"tp-1",
                    "status":"working"}}
                """);

        BracketRequest req = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                null, new BigDecimal("95"), new BigDecimal("110"), null, null);

        gw.placeBracket("saxo-sim", req);

        assertThat(gw.capturedArgs.has("limit_price")).isFalse();
        assertThat(gw.capturedArgs.has("client_ref")).isFalse();
        assertThat(gw.capturedArgs.has("time_in_force")).isFalse();
    }

    @Test void flattenSendsFractionAndMapsResult() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"closedQty":"5","remainingQty":"5","avgFillPrice":"108","orderRef":"close-1"}}
                """);

        CloseResult result = gw.flatten("saxo-sim", "ACME", new BigDecimal("0.5"));

        assertThat(gw.capturedTool).isEqualTo("flatten");
        assertThat(gw.capturedArgs.path("symbol").asString()).isEqualTo("ACME");
        assertThat(gw.capturedArgs.path("fraction").asString()).isEqualTo("0.5");
        assertThat(result.closedQty()).isEqualByComparingTo("5");
        assertThat(result.remainingQty()).isEqualByComparingTo("5");
        assertThat(result.avgFillPrice()).isEqualByComparingTo("108");
        assertThat(result.orderRef()).isEqualTo("close-1");
    }

    @Test void modifyBracketSendsOrderIdStopTargetAndMapsResult() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orderId":"stop-1","newStop":"104","newTarget":"120","accepted":true}}
                """);

        ModifyResult result = gw.modifyBracket("saxo-sim", "stop-1", new BigDecimal("104"), new BigDecimal("120"));

        assertThat(gw.capturedTool).isEqualTo("modify_bracket");
        assertThat(gw.capturedArgs.path("orderId").asString()).isEqualTo("stop-1");
        assertThat(gw.capturedArgs.path("stop").asString()).isEqualTo("104");
        assertThat(gw.capturedArgs.path("target").asString()).isEqualTo("120");
        assertThat(result.orderId()).isEqualTo("stop-1");
        assertThat(result.newStop()).isEqualByComparingTo("104");
        assertThat(result.newTarget()).isEqualByComparingTo("120");
        assertThat(result.accepted()).isTrue();
    }

    @Test void unavailableEnvelopeThrowsBrokerUnavailable() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"available\":false,\"error\":\"no session\"}}");

        assertThatThrownBy(() -> gw.account("saxo-sim"))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("no session");
    }
}
