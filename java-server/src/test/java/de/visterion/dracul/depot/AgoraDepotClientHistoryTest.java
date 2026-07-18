package de.visterion.dracul.depot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgoraDepotClientHistoryTest {

    private static class CapturingClient extends AgoraDepotClient {
        String capturedTool;
        JsonNode capturedArgs;
        JsonNode canned;
        CapturingClient(ObjectMapper mapper) { super("http://x", "tkn", mapper, 8000); }
        @Override protected JsonNode call(String tool, JsonNode args) {
            this.capturedTool = tool; this.capturedArgs = args; return canned;
        }
    }

    @Test
    void closedPositionsMapsBrokerFields() {
        var mapper = new ObjectMapper();
        var client = new CapturingClient(mapper);
        client.canned = mapper.readTree("""
            {"output":{"closedPositions":[
              {"symbol":"AAPL","openPrice":100.0,"closePrice":110.0,"profitLoss":50.0,"clientRef":"sig-1"}]}}""");

        var out = client.closedPositions("depot-1");

        assertThat(client.capturedTool).isEqualTo("get_closed_positions");
        assertThat(client.capturedArgs.path("connection").asString()).isEqualTo("depot-1");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).symbol()).isEqualTo("AAPL");
        assertThat(out.get(0).clientRef()).isEqualTo("sig-1");
    }

    @Test
    void ordersWithStatusPassesStatusArg() {
        var mapper = new ObjectMapper();
        var client = new CapturingClient(mapper);
        client.canned = mapper.readTree("""
            {"output":{"orders":[
              {"brokerOrderId":"o-1","symbol":"MSFT","side":"buy","qty":10,"type":"market","status":"filled","role":"entry"}]}}""");

        var out = client.orders("depot-1", "all");

        assertThat(client.capturedTool).isEqualTo("get_orders");
        assertThat(client.capturedArgs.path("status").asString()).isEqualTo("all");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).status()).isEqualTo("filled");
    }
}
