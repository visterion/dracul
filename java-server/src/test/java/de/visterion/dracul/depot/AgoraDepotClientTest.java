package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgoraDepotClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    /** Capturing subclass: stubs the HTTP seam, records the last (tool, args) call. */
    private static class CapturingClient extends AgoraDepotClient {
        String capturedTool;
        JsonNode capturedArgs;
        JsonNode canned;

        CapturingClient(ObjectMapper mapper) {
            super("http://x", "tkn", mapper, 8000);
        }

        @Override
        protected JsonNode call(String tool, JsonNode args) {
            this.capturedTool = tool;
            this.capturedArgs = args;
            return canned;
        }
    }

    @Test
    void listConnectionsParsesAll() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"connections":[
              {"id":"depot-1","provider":"saxo","environment":"paper","status":"OK","probedAt":"2026-07-11T06:00:00Z"},
              {"id":"depot-2","provider":"saxo","environment":"live","status":"OK"}]}}""");
        var out = client.listConnections();
        assertThat(out).hasSize(2);
        assertThat(out.get(1).environment()).isEqualTo("live");
        assertThat(client.capturedTool).isEqualTo("list_connections");
    }

    @Test
    void positionsCarryAsOf() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"asOf":"2026-07-11T06:00:00Z","positions":[
              {"symbol":"NVDA","qty":10,"avgEntryPrice":100.5,"marketValue":1848.0,"unrealizedPl":843.0,"currency":"USD"}]}}""");
        var snap = client.positions("depot-1");
        assertThat(snap.asOf()).isEqualTo("2026-07-11T06:00:00Z");
        assertThat(snap.positions()).singleElement()
            .satisfies(p -> assertThat(p.marketValue()).isEqualByComparingTo("1848.0"));
        assertThat(client.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
    }

    @Test
    void unavailableEnvelopeThrows() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("{\"output\":{\"available\":false,\"error\":\"broker down\"}}");
        assertThatThrownBy(() -> client.account("depot-1"))
            .isInstanceOf(DepotUnavailableException.class).hasMessageContaining("broker down");
    }

    @Test
    void accountReadsNestedFieldsWithAsOf() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"account":{"accountId":"acc-1","equity":10003.84,"buyingPower":9178.57,
                "cash":9178.57,"currency":"EUR","status":"ACTIVE"},"asOf":"2026-07-11T06:00:00Z"}}""");

        DepotAccount result = client.account("depot-1");

        assertThat(client.capturedTool).isEqualTo("get_account");
        assertThat(client.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(result.cash()).isEqualByComparingTo("9178.57");
        assertThat(result.equity()).isEqualByComparingTo("10003.84");
        assertThat(result.buyingPower()).isEqualByComparingTo("9178.57");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.asOf()).isEqualTo("2026-07-11T06:00:00Z");
    }

    @Test
    void accountFallsBackToRootWhenNoAccountNode() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"cash":"1000","equity":"1500","buyingPower":"2000","currency":"USD",
                "status":"ACTIVE","asOf":"2026-07-11T06:00:00Z"}}""");

        DepotAccount result = client.account("depot-1");

        assertThat(result.cash()).isEqualByComparingTo("1000");
        assertThat(result.equity()).isEqualByComparingTo("1500");
        assertThat(result.buyingPower()).isEqualByComparingTo("2000");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    void ordersParsesAllFieldsIncludingOptional() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"asOf":"2026-07-11T06:00:00Z","orders":[
              {"brokerOrderId":"ord-1","clientRef":"ref-1","symbol":"NVDA","side":"sell","qty":10,
               "type":"limit","status":"partially_filled","role":"stop_loss","filledQty":4,
               "avgFillPrice":95.0,"parentId":"brk-1"}]}}""");

        List<DepotOrder> result = client.orders("depot-1");

        assertThat(client.capturedTool).isEqualTo("get_orders");
        assertThat(client.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(result).hasSize(1);
        DepotOrder order = result.get(0);
        assertThat(order.brokerOrderId()).isEqualTo("ord-1");
        assertThat(order.symbol()).isEqualTo("NVDA");
        assertThat(order.side()).isEqualTo("sell");
        assertThat(order.qty()).isEqualByComparingTo("10");
        assertThat(order.type()).isEqualTo("limit");
        assertThat(order.status()).isEqualTo("partially_filled");
        assertThat(order.role()).isEqualTo("stop_loss");
    }

    @Test
    void ordersHandlesMissingOptionalFields() {
        CapturingClient client = new CapturingClient(mapper);
        client.canned = json("""
            {"output":{"orders":[
              {"brokerOrderId":"ord-2","symbol":"AAPL","side":"buy","qty":3,
               "type":"market","status":"new","role":"entry"}]}}""");

        List<DepotOrder> result = client.orders("depot-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).brokerOrderId()).isEqualTo("ord-2");
    }
}
