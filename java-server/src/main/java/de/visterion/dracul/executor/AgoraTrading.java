package de.visterion.dracul.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/**
 * Broker-write facade over Agora's webhook trading tools. Reaches
 * {@code POST {agora-base}/tools/{name}} with a NON-LIVE trading bearer token, so
 * saxo-live is unreachable by construction. The raw HTTP send sits behind {@link #call}
 * so tests can stub it.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class AgoraTrading {

    private final String token;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AgoraTrading(
            @Value("${dracul.executor.agora-base-url:http://agora:8080}") String baseUrl,
            @Value("${dracul.executor.agora-trading-token:}") String token,
            ObjectMapper mapper) {
        this.token = token;
        this.mapper = mapper;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public JsonNode account(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        return unwrap(call("get_account", args));
    }

    public JsonNode listConnections() {
        return unwrap(call("list_connections", mapper.createObjectNode()));
    }

    public JsonNode positions(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        return unwrap(call("list_positions", args));
    }

    /** Place a protective bracket order. Returns the parsed output (broker order id etc.). */
    public JsonNode placeBracket(String connection, String symbol, String side,
                                 BigDecimal qty, BigDecimal limitPrice,
                                 BigDecimal stopPrice, BigDecimal takeProfit) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("symbol", symbol);
        args.put("side", side);
        args.put("qty", qty);
        if (limitPrice != null) args.put("limit_price", limitPrice);
        args.put("stop_loss", stopPrice);
        if (takeProfit != null) args.put("take_profit", takeProfit);
        return unwrap(call("place_bracket_order", args));
    }

    /** Overridable HTTP seam. Returns the full {"output": ...} envelope. */
    protected JsonNode call(String tool, JsonNode args) {
        try {
            String body = http.post()
                    .uri("/tools/{name}", tool)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(args))
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new AgoraTradingException("agora trading call failed: " + tool, e);
        }
    }

    private JsonNode unwrap(JsonNode envelope) {
        JsonNode output = envelope.path("output");
        if (output.path("available").isBoolean() && !output.path("available").asBoolean(true)) {
            throw new AgoraTradingException(output.path("error").asString("agora trading tool unavailable"));
        }
        return output;
    }
}
