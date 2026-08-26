package de.visterion.dracul.executor.broker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            attachAppender() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AgoraExecutionGateway.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void detachAppenders() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgoraExecutionGateway.class))
                .detachAndStopAllAppenders();
    }

    private static List<String> logLines(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        return a.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(e -> e.getFormattedMessage())
                .toList();
    }

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

    @Test void closedPositionsMapsArray() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"closedPositions":[
                    {"symbol":"ISRG","openPrice":364.35,"closePrice":364.10,"profitLoss":-0.25,"clientRef":"sig-1"}
                ]}}
                """);

        List<BrokerClosedPosition> result = gw.closedPositions("depot-1");

        assertThat(gw.capturedTool).isEqualTo("get_closed_positions");
        assertThat(gw.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(result).hasSize(1);
        BrokerClosedPosition p = result.get(0);
        assertThat(p.symbol()).isEqualTo("ISRG");
        assertThat(p.openPrice()).isEqualByComparingTo("364.35");
        assertThat(p.closePrice()).isEqualByComparingTo("364.10");
        assertThat(p.profitLoss()).isEqualByComparingTo("-0.25");
        assertThat(p.clientRef()).isEqualTo("sig-1");
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
        assertThat(gw.capturedArgs.path("clientRef").asString()).isEqualTo("ref-1");
        assertThat(gw.capturedArgs.path("ref").isMissingNode()).isTrue();
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

    @Test void modifyBracketOmitsLegIdsWhenNotNamed() {
        // The default path must stay byte-identical for existing callers: no stopOrderId key at
        // all, not a null one — Agora reads "present" as "address this exact order".
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"orderId\":\"brk-1\",\"accepted\":true}}");

        gw.modifyBracket("depot-1", "brk-1", "ACME", new BigDecimal("104"), null);

        assertThat(gw.capturedArgs.has("stopOrderId")).isFalse();
        assertThat(gw.capturedArgs.has("targetOrderId")).isFalse();
    }

    @Test void modifyBracketSendsTheNamedStopLeg() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"orderId\":\"brk-1\",\"newStop\":\"104\",\"accepted\":true}}");

        ModifyResult result = gw.modifyBracket("depot-1", "brk-1", "ACME", new BigDecimal("104"), null,
                "stop-t2", null);

        assertThat(gw.capturedTool).isEqualTo("modify_bracket");
        assertThat(gw.capturedArgs.path("stopOrderId").asString()).isEqualTo("stop-t2");
        assertThat(gw.capturedArgs.has("targetOrderId")).isFalse();
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
        // NO_POSITION is the reject code Agora's FlattenTool actually emits when there is no open
        // position to flatten (SaxoBrokerProvider.resolveNetPosition's definite determination) --
        // not a placeholder. Deliberately distinct (fix round 2) from FlattenTool's generic
        // NOT_FOUND, which covers an HTTP 404 reached elsewhere inside flatten and says nothing
        // about whether the position exists -- see flattenThrowsOnGenericNotFoundToo below.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectCode\":\"NO_POSITION\"}}");

        assertThatThrownBy(() -> gw.flatten("depot-1", "AAPL", new BigDecimal("1")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("NO_POSITION");
    }

    @Test void flattenThrowsOnGenericNotFoundToo() {
        // The generic NOT_FOUND still crosses the boundary as a typed BrokerRejectedException --
        // this gateway does not special-case any one reject code, that discrimination belongs to
        // the caller (HardTriggerService / ExecutorWebhookController), which must NOT treat this
        // one as "position already gone".
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectCode\":\"NOT_FOUND\"}}");

        assertThatThrownBy(() -> gw.flatten("depot-1", "AAPL", new BigDecimal("1")))
                .isInstanceOf(BrokerRejectedException.class)
                .satisfies(e -> assertThat(((BrokerRejectedException) e).rejectCode())
                        .isEqualTo("NOT_FOUND"));
    }

    @Test void parsesRestoredLegsFromTheFlattenResponse() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"closedQty":"88","remainingQty":"12","avgFillPrice":"45.50","orderId":"close-1",
                    "protective_legs":[
                        {"replaces":"5039413297","order_id":"5039501122","qty":8,"price":45.49},
                        {"replaces":"5039413298","order_id":"5039501123","qty":4,"price":45.49}
                    ],
                    "legs_collapsed":false}}
                """);

        CloseResult result = gw.flatten("depot-1", "ACME", new BigDecimal("0.88"));

        assertThat(result.protectiveLegs()).hasSize(2);
        assertThat(result.protectiveLegs()).extracting(RestoredLeg::replaces)
                .containsExactlyInAnyOrder("5039413297", "5039413298");
        RestoredLeg first = result.protectiveLegs().stream()
                .filter(l -> l.replaces().equals("5039413297")).findFirst().orElseThrow();
        assertThat(first.orderId()).isEqualTo("5039501122");
        assertThat(first.qty()).isEqualByComparingTo("8");
        assertThat(first.price()).isEqualByComparingTo("45.49");
        assertThat(result.legsCollapsed()).isFalse();
    }

    @Test void closeResultHasEmptyLegsWhenTheFieldIsAbsent() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"closedQty":"100","remainingQty":"0","avgFillPrice":"108","orderId":"close-1"}}
                """);

        CloseResult result = gw.flatten("depot-1", "ACME", new BigDecimal("1"));

        assertThat(result.protectiveLegs()).isEmpty();
        assertThat(result.legsCollapsed()).isFalse();
    }

    @Test void aRejectionCarriesItsCodeAndItsRolledBackLegs() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"accepted":false,"rejectCode":"LEG_RESTORE_FAILED_UNPROTECTED",
                    "protective_legs":[
                        {"replaces":"5039413297","order_id":"5039501200","qty":12,"price":45.49}
                    ]}}
                """);

        assertThatThrownBy(() -> gw.flatten("depot-1", "ACME", new BigDecimal("0.88")))
                .isInstanceOf(BrokerRejectedException.class)
                .satisfies(e -> {
                    BrokerRejectedException rejected = (BrokerRejectedException) e;
                    assertThat(rejected.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
                    assertThat(rejected.protectiveLegs()).hasSize(1);
                    assertThat(rejected.protectiveLegs().get(0).replaces()).isEqualTo("5039413297");
                    assertThat(rejected.protectiveLegs().get(0).orderId()).isEqualTo("5039501200");
                });
    }

    @Test void brokerRejectedExceptionIsStillABrokerUnavailableException() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectCode\":\"LEG_CANCEL_INCOMPLETE\"}}");

        assertThatThrownBy(() -> gw.flatten("depot-1", "ACME", new BigDecimal("0.5")))
                .isInstanceOf(BrokerUnavailableException.class);
    }

    @Test void ordersMapsRealSaxoStatusVocabulary() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-fill","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"finalfill","qty":"10","filledQty":"10","avgFillPrice":"95","parentId":null},
                    {"brokerOrderId":"ord-work","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"working","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":null},
                    {"brokerOrderId":"ord-chg","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"changed","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":null}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result.get(0).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.get(1).status()).isEqualTo(OrderStatus.WORKING);
        assertThat(result.get(2).status()).isEqualTo(OrderStatus.WORKING);
    }

    @Test void filledOrdersSinceKeepsFinalFill() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"stop-1","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"finalfill","qty":"10","filledQty":"10","avgFillPrice":"95","parentId":null}
                ]}}
                """);

        List<BrokerOrder> result =
                gw.filledOrdersSince("depot-1", java.time.Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().orderId()).isEqualTo("stop-1");
        assertThat(result.getFirst().avgFillPrice()).isEqualByComparingTo("95");
    }

    @Test void unknownStatusMapsToWorkingAndLogsWarning() {
        // The default branch must stay logged, not silent. This test pins that regression
        // cannot happen: an unknown status maps to WORKING and emits the warning.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-weird","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"weirdstatus","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":null}
                ]}}
                """);
        var appender = attachAppender();

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.WORKING);
        assertThat(logLines(appender))
                .anySatisfy(l -> assertThat(l)
                        .contains("unmapped broker order status 'weirdstatus'")
                        .contains("treating it as WORKING")
                        .contains("terminal status hiding here makes fills unobservable"));
    }

    @Test void notworkingStatusMapsToWorkingAndLogsWarning() {
        // notworking is deliberately unmapped so it falls through to the logged default.
        // This test ensures the warning is preserved and someone cannot accidentally
        // add a case for notworking without being caught.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-notworking","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"notworking","qty":"10","filledQty":"0","avgFillPrice":null,"parentId":null}
                ]}}
                """);
        var appender = attachAppender();

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.WORKING);
        assertThat(logLines(appender))
                .anySatisfy(l -> assertThat(l)
                        .contains("unmapped broker order status 'notworking'")
                        .contains("treating it as WORKING")
                        .contains("terminal status hiding here makes fills unobservable"));
    }

    @Test void partialfillMapsToPartiallyFilled() {
        // partialfill is documented in Saxo docs but never observed on our account.
        // Pinning the mapping ensures the correct behaviour is at least covered.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"orders":[
                    {"brokerOrderId":"ord-partial","clientRef":"r","symbol":"ACME","role":"other",
                     "status":"partialfill","qty":"10","filledQty":"6","avgFillPrice":"95","parentId":null}
                ]}}
                """);

        List<BrokerOrder> result = gw.orders("depot-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(result.get(0).filledQty()).isEqualByComparingTo("6");
    }

    @Test void modifyBracketThrowsOnRejection() {
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("{\"output\":{\"accepted\":false,\"rejectReason\":\"unknown order\"}}");

        assertThatThrownBy(() -> gw.modifyBracket("depot-1", "brk-1", "AAPL",
                new BigDecimal("104"), new BigDecimal("120")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("unknown order");
    }

    @Test void positionsDerivesPerUnitMarketPriceFromMarketValueWhenNoMarketPriceField() {
        // Pre-A4.1 Agora shape: no per-unit marketPrice field, only the total marketValue.
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
        // marketValue / qty = 312.0 / 3.0 = 104.0, NEVER the raw 312.0 total.
        assertThat(p.marketPrice()).isEqualByComparingTo("104.0");
        // Live Saxo has no side field.
        assertThat(p.side()).isNull();
    }

    @Test void marketPriceReadFromPerUnitField() {
        // Agora A4.1 payload: marketPrice per-unit present alongside the total marketValue.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"positions":[
                    {"symbol":"PSMT","qty":5,"avgEntryPrice":193.87,"marketPrice":192.56,
                     "marketValue":962.80,"openOrdersCount":1}
                ]}}
                """);

        BrokerPosition p = gw.positions("depot-1").get(0);

        assertThat(p.marketPrice()).isEqualByComparingTo("192.56"); // NEVER 962.80
        assertThat(p.openOrdersCount()).isEqualTo(1);
    }

    @Test void fallbackDividesMarketValueByQty() {
        // pre-A4.1 Agora: no marketPrice field at all.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"positions":[
                    {"symbol":"PSMT","qty":5,"marketValue":962.80}
                ]}}
                """);

        BrokerPosition p = gw.positions("depot-1").get(0);

        assertThat(p.marketPrice()).isEqualByComparingTo("192.56");
        assertThat(p.openOrdersCount()).isNull();
    }

    @Test void zeroQtyFallbackYieldsNullMarketPrice() {
        // qty 0, no marketPrice field -> dividing by zero is skipped, marketPrice stays null.
        CapturingGateway gw = new CapturingGateway(mapper);
        gw.canned = json("""
                {"output":{"positions":[
                    {"symbol":"PSMT","qty":0,"marketValue":0}
                ]}}
                """);

        BrokerPosition p = gw.positions("depot-1").get(0);

        assertThat(p.marketPrice()).isNull();
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
