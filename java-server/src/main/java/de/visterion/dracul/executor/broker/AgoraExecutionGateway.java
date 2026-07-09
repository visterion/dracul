package de.visterion.dracul.executor.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Broker-write adapter over Agora's webhook trading tools. Reaches
 * {@code POST {agora-base}/tools/{name}} with a NON-LIVE trading bearer token, so
 * saxo-live is unreachable by construction. The raw HTTP send sits behind {@link #call}
 * so tests can stub it.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class AgoraExecutionGateway implements ExecutionGateway {

    private final String token;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AgoraExecutionGateway(
            @Value("${dracul.executor.agora-base-url:http://agora:8080}") String baseUrl,
            @Value("${dracul.executor.agora-trading-token:}") String token,
            ObjectMapper mapper) {
        this.token = token;
        this.mapper = mapper;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public AccountSnapshot account(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_account", args));
        return new AccountSnapshot(
                decimalField(out, "cash", "cash"),
                decimalField(out, "buyingPower", "buying_power"),
                textOrNull(out, "currency"));
    }

    @Override
    public List<BrokerPosition> positions(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_positions", args));

        JsonNode array = out.path("positions");
        if (!array.isArray()) array = out;

        List<BrokerPosition> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode p : array) {
                result.add(new BrokerPosition(
                        textOrNull(p, "symbol"),
                        textOrNull(p, "side"),
                        decimalField(p, "qty", "qty"),
                        decimalField(p, "avgEntryPrice", "avg_entry_price"),
                        decimalField(p, "marketPrice", "market_price")));
            }
        }
        return result;
    }

    @Override
    public List<BrokerOrder> orders(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_orders", args));

        JsonNode array = out.path("orders");
        if (!array.isArray()) array = out;

        List<BrokerOrder> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode o : array) {
                result.add(toBrokerOrder(o));
            }
        }
        return result;
    }

    @Override
    public Optional<BrokerOrder> orderByRef(String connection, String ref) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("ref", ref);
        JsonNode out = unwrap(call("get_order_by_ref", args));

        JsonNode order = out.path("order");
        if (order.isMissingNode() || order.isNull()) order = out;
        if (order.path("brokerOrderId").isMissingNode() && order.path("broker_order_id").isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(toBrokerOrder(order));
    }

    /** Wire field names per {@code agora/documentation/exit-tools.md}'s get_orders/get_order_by_ref
     *  contract: {@code brokerOrderId, clientRef, symbol, side, qty, type, status, role, filledQty,
     *  avgFillPrice, parentId}. */
    private BrokerOrder toBrokerOrder(JsonNode o) {
        return new BrokerOrder(
                textOrNull(o, "brokerOrderId", "broker_order_id"),
                textOrNull(o, "clientRef", "client_ref"),
                textOrNull(o, "symbol"),
                toRole(textOrNull(o, "role")),
                toStatus(textOrNull(o, "status")),
                decimalField(o, "qty", "qty"),
                decimalField(o, "filledQty", "filled_qty"),
                decimalField(o, "avgFillPrice", "avg_fill_price"),
                textOrNull(o, "parentId", "parent_id"));
    }

    /** {@code role} is documented as always present, lowercase: entry|stop_loss|take_profit|other. */
    private OrderRole toRole(String role) {
        if (role == null) return OrderRole.OTHER;
        return switch (role.toLowerCase()) {
            case "entry" -> OrderRole.ENTRY;
            case "stop_loss" -> OrderRole.STOP_LOSS;
            case "take_profit" -> OrderRole.TAKE_PROFIT;
            default -> OrderRole.OTHER;
        };
    }

    /** Anything not explicitly terminal/working-named (new/accepted/pending_new/held/working/open/…)
     *  maps to WORKING — brokers vary in their exact working-state vocabulary. */
    private OrderStatus toStatus(String status) {
        if (status == null) return OrderStatus.WORKING;
        return switch (status.toLowerCase()) {
            case "filled" -> OrderStatus.FILLED;
            case "partially_filled", "partial" -> OrderStatus.PARTIALLY_FILLED;
            case "cancelled", "canceled" -> OrderStatus.CANCELLED;
            case "rejected" -> OrderStatus.REJECTED;
            default -> OrderStatus.WORKING;
        };
    }

    @Override
    public PlacedBracket placeBracket(String connection, BracketRequest req) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("symbol", req.symbol());
        args.put("side", req.side());
        args.put("qty", req.qty());
        if (req.limitPrice() != null) args.put("limit_price", req.limitPrice());
        args.put("stop_loss_stop", req.stopLossStop());
        args.put("take_profit_limit", req.takeProfitLimit());
        if (req.clientRef() != null) args.put("client_ref", req.clientRef());
        if (req.timeInForce() != null) args.put("time_in_force", req.timeInForce());

        JsonNode out = unwrap(call("place_bracket", args));
        return new PlacedBracket(
                textOrNull(out, "orderId", "order_id"),
                textOrNull(out, "stopLegId", "stop_leg_id"),
                textOrNull(out, "takeProfitLegId", "take_profit_leg_id"),
                textOrNull(out, "clientRef", "client_ref"),
                toStatus(textOrNull(out, "status")));
    }

    @Override
    public CloseResult flatten(String connection, String symbol, BigDecimal fraction) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("symbol", symbol);
        args.put("fraction", fraction);

        JsonNode out = unwrap(call("flatten", args));
        return new CloseResult(
                decimalField(out, "closedQty", "closed_qty"),
                decimalField(out, "remainingQty", "remaining_qty"),
                decimalField(out, "avgFillPrice", "avg_fill_price"),
                textOrNull(out, "orderId", "order_id"));
    }

    @Override
    public ModifyResult modifyBracket(String connection, String orderId, String symbol, BigDecimal stop, BigDecimal target) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("orderId", orderId);
        args.put("symbol", symbol);
        if (stop != null) args.put("stop", stop);
        if (target != null) args.put("target", target);

        JsonNode out = unwrap(call("modify_bracket", args));
        return new ModifyResult(
                textOrNull(out, "orderId", "order_id"),
                decimalField(out, "newStop", "new_stop"),
                decimalField(out, "newTarget", "new_target"),
                out.path("accepted").asBoolean(true));
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private String textOrNull(JsonNode node, String camel, String snake) {
        String v = textOrNull(node, camel);
        return v != null ? v : textOrNull(node, snake);
    }

    private BigDecimal decimalField(JsonNode node, String camel, String snake) {
        JsonNode v = node.path(camel);
        if (v.isMissingNode() || v.isNull()) v = node.path(snake);
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
            throw new BrokerUnavailableException("agora trading call failed: " + tool, e);
        }
    }

    private JsonNode unwrap(JsonNode envelope) {
        JsonNode output = envelope.path("output");
        if (output.path("available").isBoolean() && !output.path("available").asBoolean(true)) {
            throw new BrokerUnavailableException(output.path("error").asString("agora trading tool unavailable"));
        }
        return output;
    }
}
