package de.visterion.dracul.depot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Readonly-token HTTP client over Agora's depot read tools (connections, account, positions,
 * orders). Reaches {@code POST {agora-base}/tools/{name}} with the depots readonly bearer token
 * — GUI-only read path, deliberately separate from {@code executor.broker.AgoraExecutionGateway}
 * (which uses a NON-LIVE trading token). The raw HTTP send sits behind {@link #call} so tests can
 * stub it. No {@code @ConditionalOnProperty}: depots must work even with the executor disabled.
 */
@Component
public class AgoraDepotClient {

    private final String token;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AgoraDepotClient(
            @Value("${dracul.depots.agora-base-url:http://agora:8080}") String baseUrl,
            @Value("${dracul.depots.agora-readonly-token:}") String token,
            ObjectMapper mapper,
            @Value("${dracul.depots.agora-timeout-ms:8000}") long timeoutMs) {
        this.token = token;
        this.mapper = mapper;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<DepotConnection> listConnections() {
        JsonNode out = unwrap(call("list_connections", mapper.createObjectNode()));

        JsonNode array = out.path("connections");
        if (!array.isArray()) array = out;

        List<DepotConnection> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode c : array) {
                result.add(new DepotConnection(
                        textOrNull(c, "id"),
                        textOrNull(c, "provider"),
                        textOrNull(c, "environment"),
                        textOrNull(c, "status"),
                        textOrNull(c, "probedAt")));
            }
        }
        return result;
    }

    public DepotAccount account(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_account", args));

        // Live Agora nests account fields under "account" (camelCase); fall back to the
        // envelope root for shapes that put them there directly.
        JsonNode acct = out.path("account");
        if (acct.isMissingNode() || acct.isNull()) acct = out;

        return new DepotAccount(
                decimalOrNull(acct, "cash"),
                decimalOrNull(acct, "equity"),
                decimalOrNull(acct, "buyingPower"),
                textOrNull(acct, "currency"),
                textOrNull(acct, "status"),
                textOrNull(out, "asOf"));
    }

    public PositionsSnapshot positions(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_positions", args));

        JsonNode array = out.path("positions");
        if (!array.isArray()) array = out;

        List<DepotPosition> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode p : array) {
                result.add(new DepotPosition(
                        textOrNull(p, "symbol"),
                        textOrNull(p, "description"),
                        decimalOrNull(p, "qty"),
                        decimalOrNull(p, "avgEntryPrice"),
                        decimalOrNull(p, "marketValue"),
                        decimalOrNull(p, "unrealizedPl"),
                        textOrNull(p, "currency"),
                        textOrNull(p, "assetType"),
                        textOrNull(p, "valueDate")));
            }
        }
        return new PositionsSnapshot(result, textOrNull(out, "asOf"));
    }

    public List<DepotOrder> orders(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_orders", args));

        JsonNode array = out.path("orders");
        if (!array.isArray()) array = out;

        List<DepotOrder> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode o : array) {
                result.add(new DepotOrder(
                        textOrNull(o, "brokerOrderId"),
                        textOrNull(o, "symbol"),
                        textOrNull(o, "side"),
                        decimalOrNull(o, "qty"),
                        textOrNull(o, "type"),
                        textOrNull(o, "status"),
                        textOrNull(o, "role")));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        return new BigDecimal(v.asString());
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
            throw new DepotUnavailableException("agora depot call failed: " + tool, e);
        }
    }

    private JsonNode unwrap(JsonNode envelope) {
        JsonNode output = envelope.path("output");
        if (output.path("available").isBoolean() && !output.path("available").asBoolean(true)) {
            throw new DepotUnavailableException(output.path("error").asString("agora depot tool unavailable"));
        }
        return output;
    }
}
