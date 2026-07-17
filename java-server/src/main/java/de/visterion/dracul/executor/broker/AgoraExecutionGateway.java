package de.visterion.dracul.executor.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
            ObjectMapper mapper,
            @Value("${dracul.executor.agora-timeout-ms:8000}") long timeoutMs) {
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

    @Override
    public AccountSnapshot account(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_account", args));
        // Live Agora/saxo-sim nests account fields under "account" (camelCase); fall back to
        // the envelope root for shapes that put them there directly.
        JsonNode acct = out.path("account");
        if (acct.isMissingNode() || acct.isNull()) acct = out;
        return new AccountSnapshot(
                decimalField(acct, "cash", "cash"),
                decimalField(acct, "buyingPower", "buying_power"),
                textOrNull(acct, "currency"));
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
                BigDecimal qty = decimalField(p, "qty", "qty");
                BigDecimal marketPrice = decimalField(p, "marketPrice", "market_price");
                if (marketPrice == null) {
                    // Transitional fallback for pre-A4.1 Agora: derive per-unit from the total.
                    // Consuming raw marketValue as a per-unit price corrupted R/MFE (PSMT 2026-07-13).
                    BigDecimal marketValue = decimalField(p, "marketValue", "market_value");
                    if (marketValue != null && qty != null && qty.signum() != 0) {
                        marketPrice = marketValue.divide(qty, 12, RoundingMode.HALF_UP).stripTrailingZeros();
                    }
                }
                JsonNode ooc = p.path("openOrdersCount");
                result.add(new BrokerPosition(
                        textOrNull(p, "symbol"),
                        // Live Saxo returns no "side" field — leave it null rather than invent one.
                        textOrNull(p, "side"),
                        qty,
                        decimalField(p, "avgEntryPrice", "avg_entry_price"),
                        marketPrice,
                        ooc.isIntegralNumber() ? ooc.asInt() : null));
            }
        }
        return result;
    }

    @Override
    public List<BrokerClosedPosition> closedPositions(String connection) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        JsonNode out = unwrap(call("get_closed_positions", args));
        JsonNode array = out.path("closedPositions");
        if (!array.isArray()) array = out;
        List<BrokerClosedPosition> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode c : array) {
                result.add(new BrokerClosedPosition(
                        textOrNull(c, "symbol"),
                        decimalField(c, "openPrice", "open_price"),
                        decimalField(c, "closePrice", "close_price"),
                        decimalField(c, "profitLoss", "profit_loss"),
                        textOrNull(c, "clientRef", "client_ref")));
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
        args.put("clientRef", ref);
        JsonNode out = unwrap(call("get_order_by_ref", args));

        JsonNode order = out.path("order");
        if (order.isMissingNode() || order.isNull()) order = out;
        if (order.path("brokerOrderId").isMissingNode() && order.path("broker_order_id").isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(toBrokerOrder(order));
    }

    // KNOWN LIMITATION: live Saxo working orders carry NO role, NO parentId, and NO
    // filledQty/avgFillPrice — the real get_orders shape is
    // {brokerOrderId, clientRef, symbol, side, qty, type, status}. We derive a best-effort
    // role hint from the order "type" (see roleOf), but this means reconcile CANNOT reliably
    // match exit legs to their bracket by role/parentId. A future fix must either group by
    // clientRef or have Agora expose an explicit role/parentId per order.
    private BrokerOrder toBrokerOrder(JsonNode o) {
        return new BrokerOrder(
                textOrNull(o, "brokerOrderId", "broker_order_id"),
                textOrNull(o, "clientRef", "client_ref"),
                textOrNull(o, "symbol"),
                roleOf(o),
                toStatus(textOrNull(o, "status")),
                decimalField(o, "qty", "qty"),
                decimalField(o, "filledQty", "filled_qty"),
                decimalField(o, "avgFillPrice", "avg_fill_price"),
                textOrNull(o, "parentId", "parent_id"));
    }

    /**
     * Best-effort order role. Prefers an explicit {@code role} (lowercase
     * entry|stop_loss|take_profit|other) if a broker/Agora ever supplies one; otherwise falls
     * back to the live Saxo {@code type}: stopiftraded/stop -> STOP_LOSS, everything else
     * (incl. plain "limit", which is ambiguous between entry and take-profit) -> OTHER.
     */
    private OrderRole roleOf(JsonNode o) {
        String role = textOrNull(o, "role");
        if (role != null) {
            return switch (role.toLowerCase()) {
                case "entry" -> OrderRole.ENTRY;
                case "stop_loss" -> OrderRole.STOP_LOSS;
                case "take_profit" -> OrderRole.TAKE_PROFIT;
                default -> OrderRole.OTHER;
            };
        }
        String type = textOrNull(o, "type");
        if (type == null) return OrderRole.OTHER;
        return switch (type.toLowerCase()) {
            case "stopiftraded", "stop" -> OrderRole.STOP_LOSS;
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
        // Live Agora rejects snake_case arg names ("missing required argument: stopLossStop");
        // the wire contract is camelCase.
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("symbol", req.symbol());
        args.put("side", req.side() == null ? null : req.side().toLowerCase(java.util.Locale.ROOT));
        args.put("qty", req.qty());
        if (req.limitPrice() != null) args.put("limitPrice", req.limitPrice());
        args.put("stopLossStop", req.stopLossStop());
        args.put("takeProfitLimit", req.takeProfitLimit());
        if (req.clientRef() != null) args.put("clientRef", req.clientRef());
        if (req.timeInForce() != null) args.put("timeInForce", req.timeInForce());

        JsonNode out = unwrap(call("place_bracket", args));
        requireAccepted(out);
        // Saxo does not return stopLegId/takeProfitLegId — leave them null (expected).
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
        requireAccepted(out);
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
        requireAccepted(out);
        return new ModifyResult(
                textOrNull(out, "orderId", "order_id"),
                decimalField(out, "newStop", "new_stop"),
                decimalField(out, "newTarget", "new_target"),
                out.path("accepted").asBoolean(true));
    }

    @Override
    public void cancelOrder(String connection, String orderId) {
        ObjectNode args = mapper.createObjectNode();
        args.put("connection", connection);
        args.put("orderId", orderId);

        JsonNode out = unwrap(call("cancel_order", args));
        requireAccepted(out);
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

    private BigDecimal decimalField(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.path(name);
            if (!v.isMissingNode() && !v.isNull()) return new BigDecimal(v.asString());
        }
        return null;
    }

    /**
     * Live Agora write tools (place_bracket, flatten, modify_bracket, cancel_order) return an
     * {@code accepted} flag; a business rejection is {@code accepted:false} with
     * {@code rejectCode}/{@code rejectReason}. Treat that as unavailable so a rejected order is
     * never silently returned as a success with a null orderId.
     */
    private void requireAccepted(JsonNode out) {
        JsonNode accepted = out.path("accepted");
        if (accepted.isBoolean() && !accepted.asBoolean()) {
            String code = textOrNull(out, "rejectCode", "reject_code");
            String reason = textOrNull(out, "rejectReason", "reject_reason");
            throw new BrokerUnavailableException("agora order rejected"
                    + (code != null ? " [" + code + "]" : "")
                    + (reason != null ? ": " + reason : ""));
        }
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
