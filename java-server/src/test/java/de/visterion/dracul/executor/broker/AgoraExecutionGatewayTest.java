package de.visterion.dracul.executor.broker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
            super("http://x", "tkn", mapper, 8000);
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

        AccountSnapshot result = gw.account("depot-1");

        assertThat(gw.capturedTool).isEqualTo("get_account");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
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

        List<BrokerPosition> result = gw.positions("depot-1");

        assertThat(gw.capturedTool).isEqualTo("get_positions");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("ACME");
        assertThat(result.get(0).qty()).isEqualByComparingTo("10");
        assertThat(result.get(0).marketPrice()).isEqualByComparingTo("108");
    }

    @Test void ordersMapsRoleAndStatusAndFillInfo() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-1","clientRef":"ref-1","symbol":"ACME","side":"sell",
                     "role":"stop_loss","status":"partially_filled","qty":"10","filledQty":"4",
                     "avgFillPrice":"95","parentId":"brk-1"}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("depot-1");

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

    @Test void ordersMapsAllRolesAndStatuses() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-entry","clientRef":"r","symbol":"ACME","role":"entry",
                     "status":"filled","qty":"10","filledQty":"10","avgFillPrice":"100","parentId":null},
                    {"brokerOrderId":"ord-tp","clientRef":"r","symbol":"ACME","role":"take_profit",
                     "status":"new","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":"ord-entry"},
                    {"brokerOrderId":"ord-other","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"cancelled","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":null}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).role()).isEqualTo(OrderRole.ENTRY);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.get(1).role()).isEqualTo(OrderRole.TAKE_PROFIT);
        assertThat(result.get(1).status()).isEqualTo(OrderStatus.WORKING);
        assertThat(result.get(2).role()).isEqualTo(OrderRole.OTHER);
        assertThat(result.get(2).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test void orderByRefFound() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"order":{"brokerOrderId":"ord-1","clientRef":"ref-1","symbol":"ACME",
                    "role":"entry","status":"working","qty":"10","filledQty":"0"}}}
                """);

        Optional<BrokerOrder> result = gw.orderByRef("depot-1", "ref-1");

        assertThat(gw.capturedTool).isEqualTo("get_order_by_ref");
        assertThat(gw.capturedArgs.path("ref").asString()).isEqualTo("ref-1");
        assertThat(result).isPresent();
        assertThat(result.get().orderId()).isEqualTo("ord-1");
        assertThat(result.get().role()).isEqualTo(OrderRole.ENTRY);
    }

    @Test void orderByRefEmptyWhenMissing() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{}}");

        Optional<BrokerOrder> result = gw.orderByRef("depot-1", "nope");

        assertThat(result).isEmpty();
    }

    @Test void placeBracketBuildsArgsAndMapsIds() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orderId":"brk-1","stopLegId":"stop-1","takeProfitLegId":"tp-1",
                    "clientRef":"sig-1","status":"working"}}
                """);

        BracketRequest req = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), "sig-1", "DAY");

        PlacedBracket result = gw.placeBracket("depot-1", req);

        assertThat(gw.capturedTool).isEqualTo("place_bracket");
        JsonNode args = gw.capturedArgs;
        assertThat(args.path("connection").asString()).isEqualTo("depot-1");
        assertThat(args.path("symbol").asString()).isEqualTo("ACME");
        assertThat(args.path("side").asString()).isEqualTo("buy");
        assertThat(args.has("qty")).isTrue();
        // Agora requires camelCase arg names on the wire.
        assertThat(args.has("stopLossStop")).isTrue();
        assertThat(args.has("takeProfitLimit")).isTrue();
        assertThat(args.has("limitPrice")).isTrue();
        assertThat(args.has("timeInForce")).isTrue();
        assertThat(args.path("clientRef").asString()).isEqualTo("sig-1");
        // and NOT the old snake_case names.
        assertThat(args.has("stop_loss_stop")).isFalse();
        assertThat(args.has("take_profit_limit")).isFalse();
        assertThat(args.has("client_ref")).isFalse();

        assertThat(result.bracketId()).isEqualTo("brk-1");
        assertThat(result.stopLegId()).isEqualTo("stop-1");
        assertThat(result.takeProfitLegId()).isEqualTo("tp-1");
        assertThat(result.status()).isEqualTo(OrderStatus.WORKING);
    }

    @Test void placeBracketLowercasesSideForAgora() {
        // Root cause: Agora's PlaceBracketTool validates `side` case-sensitively against
        // lowercase "buy"/"sell", but Dracul's domain uses uppercase BUY/SELL end-to-end.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orderId":"brk-1","stopLegId":"stop-1","takeProfitLegId":"tp-1",
                    "clientRef":"sig-1","status":"working"}}
                """);

        BracketRequest buyReq = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), "sig-1", "DAY");
        gw.placeBracket("depot-1", buyReq);
        assertThat(gw.capturedArgs.path("side").asString()).isEqualTo("buy");

        BracketRequest sellReq = new BracketRequest("ACME", "SELL", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), "sig-1", "DAY");
        gw.placeBracket("depot-1", sellReq);
        assertThat(gw.capturedArgs.path("side").asString()).isEqualTo("sell");
    }

    @Test void placeBracketOmitsOptionalArgs() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orderId":"brk-1","stopLegId":"stop-1","takeProfitLegId":"tp-1",
                    "status":"working"}}
                """);

        BracketRequest req = new BracketRequest("ACME", "BUY", new BigDecimal("10"),
                null, new BigDecimal("95"), new BigDecimal("110"), null, null);

        gw.placeBracket("depot-1", req);

        assertThat(gw.capturedArgs.has("limitPrice")).isFalse();
        assertThat(gw.capturedArgs.has("clientRef")).isFalse();
        assertThat(gw.capturedArgs.has("timeInForce")).isFalse();
    }

    @Test void flattenSendsFractionAndMapsResult() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"closedQty":"5","remainingQty":"5","avgFillPrice":"108","orderId":"close-1"}}
                """);

        CloseResult result = gw.flatten("depot-1", "ACME", new BigDecimal("0.5"));

        assertThat(gw.capturedTool).isEqualTo("flatten");
        assertThat(gw.capturedArgs.path("symbol").asString()).isEqualTo("ACME");
        assertThat(gw.capturedArgs.path("fraction").asString()).isEqualTo("0.5");
        assertThat(result.closedQty()).isEqualByComparingTo("5");
        assertThat(result.remainingQty()).isEqualByComparingTo("5");
        assertThat(result.avgFillPrice()).isEqualByComparingTo("108");
        assertThat(result.orderRef()).isEqualTo("close-1");
    }

    @Test void modifyBracketSendsOrderIdSymbolStopTargetAndMapsResult() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orderId":"brk-1","newStop":"104","newTarget":"120","accepted":true}}
                """);

        ModifyResult result = gw.modifyBracket("depot-1", "brk-1", "ACME", new BigDecimal("104"), new BigDecimal("120"));

        assertThat(gw.capturedTool).isEqualTo("modify_bracket");
        assertThat(gw.capturedArgs.path("orderId").asString()).isEqualTo("brk-1");
        assertThat(gw.capturedArgs.path("symbol").asString()).isEqualTo("ACME");
        assertThat(gw.capturedArgs.path("stop").asString()).isEqualTo("104");
        assertThat(gw.capturedArgs.path("target").asString()).isEqualTo("120");
        assertThat(result.orderId()).isEqualTo("brk-1");
        assertThat(result.newStop()).isEqualByComparingTo("104");
        assertThat(result.newTarget()).isEqualByComparingTo("120");
        assertThat(result.accepted()).isTrue();
    }

    @Test void cancelOrderSendsConnectionAndOrderId() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":true,\"orderId\":\"brk-1\",\"status\":\"cancelled\"}}");

        gw.cancelOrder("depot-1", "brk-1");

        assertThat(gw.capturedTool).isEqualTo("cancel_order");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(gw.capturedArgs.path("orderId").asString()).isEqualTo("brk-1");
    }

    @Test void cancelOrderThrowsOnRejection() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectCode\":\"UnknownOrder\"}}");

        assertThatThrownBy(() -> gw.cancelOrder("depot-1", "brk-1"))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("UnknownOrder");
    }

    @Test void unavailableEnvelopeThrowsBrokerUnavailable() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"available\":false,\"error\":\"no session\"}}");

        assertThatThrownBy(() -> gw.account("depot-1"))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("no session");
    }

    // -------------------------------------------------------------------
    // Live Agora/depot-1 real wire shapes (captured 2026-07-09)
    // -------------------------------------------------------------------

    @Test void accountReadsNestedCamelCaseFields() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"account":{"accountId":"acc-1","equity":10003.84,"buyingPower":9178.57,
                    "cash":9178.57,"currency":"EUR","status":"ACTIVE"}}}
                """);

        AccountSnapshot result = gw.account("depot-1");

        assertThat(result.cash()).isEqualByComparingTo("9178.57");
        assertThat(result.buyingPower()).isEqualByComparingTo("9178.57");
        assertThat(result.currency()).isEqualTo("EUR");
    }

    @Test void placeBracketMapsOrderIdOnAccepted() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"accepted":true,"orderId":"5039135626","clientRef":"sig-1","status":"accepted"}}
                """);

        BracketRequest req = new BracketRequest("AAPL", "BUY", new BigDecimal("3"),
                new BigDecimal("300"), new BigDecimal("290"), new BigDecimal("320"), "sig-1", "DAY");

        PlacedBracket result = gw.placeBracket("depot-1", req);

        assertThat(result.bracketId()).isEqualTo("5039135626");
        assertThat(result.clientRef()).isEqualTo("sig-1");
        // Saxo returns no leg ids — expected null.
        assertThat(result.stopLegId()).isNull();
        assertThat(result.takeProfitLegId()).isNull();
        assertThat(result.status()).isEqualTo(OrderStatus.WORKING);
    }

    @Test void placeBracketThrowsOnRejection() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"accepted":false,"rejectReason":"Order-Preis ist zu weit vom Markt entfernt",
                    "rejectCode":"TooFarFromEntryOrder"}}
                """);

        BracketRequest req = new BracketRequest("AAPL", "BUY", new BigDecimal("3"),
                new BigDecimal("300"), new BigDecimal("290"), new BigDecimal("320"), "sig-1", "DAY");

        assertThatThrownBy(() -> gw.placeBracket("depot-1", req))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("TooFarFromEntryOrder");
    }

    @Test void flattenThrowsOnRejection() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectCode\":\"NoPosition\"}}");

        assertThatThrownBy(() -> gw.flatten("depot-1", "AAPL", new BigDecimal("1")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("NoPosition");
    }

    @Test void modifyBracketThrowsOnRejection() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectReason\":\"unknown order\"}}");

        assertThatThrownBy(() -> gw.modifyBracket("depot-1", "brk-1", "AAPL",
                new BigDecimal("104"), new BigDecimal("120")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("unknown order");
    }

    @Test void positionsReadsMarketValueAsMarketPrice() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"positions":[
                    {"symbol":"AAPL","qty":3.0,"avgEntryPrice":307.59,"marketValue":312.0,
                     "unrealizedPl":22.53,"currency":"USD"}
                ]}}
                """);

        List<BrokerPosition> result = gw.positions("depot-1");

        assertThat(result).hasSize(1);
        BrokerPosition p = result.get(0);
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.qty()).isEqualByComparingTo("3.0");
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("307.59");
        assertThat(p.marketPrice()).isEqualByComparingTo("312.0");
        // Live Saxo has no side field.
        assertThat(p.side()).isNull();
    }

    @Test void ordersDerivesStopLossRoleFromType() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"5039135626","clientRef":"sig-1","symbol":"AAPL","side":"sell",
                     "qty":3.0,"type":"stopiftraded","status":"working"},
                    {"brokerOrderId":"5039135627","clientRef":"sig-1","symbol":"AAPL","side":"sell",
                     "qty":3.0,"type":"limit","status":"working"}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(OrderRole.STOP_LOSS);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.WORKING);
        // plain "limit" is ambiguous (entry vs take-profit) -> OTHER, never guessed.
        assertThat(result.get(1).role()).isEqualTo(OrderRole.OTHER);
    }

    @Test void hungAgoraCallFailsFastWithTimeout() throws Exception {
        // Real HTTP server (not the overridden `call` seam) so the RestClient's own timeout is
        // exercised: a handler that sleeps far longer than the configured timeout must still
        // surface as BrokerUnavailableException rather than blocking the caller.
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tools/get_account", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AgoraExecutionGateway gw = new AgoraExecutionGateway(
                    "http://localhost:" + server.getAddress().getPort(), "tkn", mapper, 200);

            assertThatThrownBy(() -> gw.account("depot-1"))
                    .isInstanceOf(BrokerUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }
}
