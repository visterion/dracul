package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 5 tool webhooks + completion callback for the Dracul executor agent.
 *
 * <p>{@code place-entry} is the safety-critical core: the LLM only <em>requests</em> an
 * entry, code decides. Every rejection path (schema, veto, order guard, broker error)
 * short-circuits before any call to {@link ExecutionGateway#placeBracket}.
 */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor")
public class ExecutorWebhookController {

    private final BearerTokenVerifier verifier;
    private final ExecutorSignalRepository signalRepo;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorDecisionRepository decisionRepo;
    private final VetoService vetoService;
    private final OrderGuard orderGuard;
    private final ExecutionGateway gateway;
    private final ExecutorIndicators executorIndicators;
    private final ObjectMapper mapper;

    private final String connection;
    private final double minConfidence;
    private final int maxPositions;
    private final int atrPeriod;
    private final int swingPeriod;

    public ExecutorWebhookController(
            ExecutorSignalRepository signalRepo,
            ExecutorPositionRepository positionRepo,
            ExecutorDecisionRepository decisionRepo,
            VetoService vetoService,
            OrderGuard orderGuard,
            ExecutionGateway gateway,
            ExecutorIndicators executorIndicators,
            ObjectMapper mapper,
            @Value("${dracul.executor.webhook-token:}") String webhookToken,
            @Value("${dracul.executor.connection:saxo-sim}") String connection,
            @Value("${dracul.executor.min-confidence:0.6}") double minConfidence,
            @Value("${dracul.executor.max-positions:5}") int maxPositions,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {

        this.signalRepo = signalRepo;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.vetoService = vetoService;
        this.orderGuard = orderGuard;
        this.gateway = gateway;
        this.executorIndicators = executorIndicators;
        this.mapper = mapper;
        this.connection = connection;
        this.minConfidence = minConfidence;
        this.maxPositions = maxPositions;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
        this.verifier = new BearerTokenVerifier(webhookToken);
    }

    // -------------------------------------------------------------------
    // fetch-pending-signals
    // -------------------------------------------------------------------

    @PostMapping("/tools/fetch-pending-signals")
    public ResponseEntity<Map<String, Object>> fetchPendingSignals(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        List<Map<String, Object>> signals = new ArrayList<>();
        for (ExecutorSignal s : signalRepo.findPending(50)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("signal_id", s.signalId());
            node.put("symbol", s.symbol());
            node.put("direction", s.direction());
            node.put("confidence", s.confidence());
            node.put("mechanism", s.mechanism());
            node.put("kill_criteria", s.killCriteria());
            node.put("horizon", s.horizon());

            ExecutorIndicators.Levels levels = executorIndicators.levels(s.symbol(), atrPeriod, swingPeriod);
            if (levels.available()) {
                node.put("atr", levels.atr());
                node.put("swing_low", levels.swingLow());
                node.put("reference_price", levels.referencePrice());
            } else {
                node.put("atr", null);
                node.put("swing_low", null);
                node.put("reference_price", s.referencePrice());
            }
            signals.add(node);
        }
        return ResponseEntity.ok(Map.of("output", Map.of("signals", signals)));
    }

    // -------------------------------------------------------------------
    // get-account / list-positions
    // -------------------------------------------------------------------

    @PostMapping("/tools/get-account")
    public ResponseEntity<Map<String, Object>> getAccount(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        String conn = resolveConnection(body);
        try {
            AccountSnapshot snapshot = gateway.account(conn);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("cash", snapshot.cash());
            output.put("buying_power", snapshot.buyingPower());
            output.put("currency", snapshot.currency());
            return ResponseEntity.ok(Map.of("output", output));
        } catch (BrokerUnavailableException e) {
            return ResponseEntity.ok(Map.of("output",
                    Map.of("available", false, "error", e.getMessage())));
        }
    }

    @PostMapping("/tools/list-positions")
    public ResponseEntity<Map<String, Object>> listPositions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        String conn = resolveConnection(body);
        try {
            List<BrokerPosition> positions = gateway.positions(conn);
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (BrokerPosition p : positions) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("symbol", p.symbol());
                node.put("side", p.side());
                node.put("qty", p.qty());
                node.put("avg_entry_price", p.avgEntryPrice());
                node.put("market_price", p.marketPrice());
                serialized.add(node);
            }
            return ResponseEntity.ok(Map.of("output", Map.of("positions", serialized)));
        } catch (BrokerUnavailableException e) {
            return ResponseEntity.ok(Map.of("output",
                    Map.of("available", false, "error", e.getMessage())));
        }
    }

    private String resolveConnection(JsonNode body) {
        if (body == null) return connection;
        return body.path("connection").asString(connection);
    }

    // -------------------------------------------------------------------
    // place-entry — the guarded core
    // -------------------------------------------------------------------

    @PostMapping("/tools/place-entry")
    public ResponseEntity<Map<String, Object>> placeEntry(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        if (body == null) body = mapper.createObjectNode();

        String signalId = body.path("signal_id").asString("");
        String bodySymbol = body.path("symbol").asString("");
        String side = body.path("side").asString(null);
        BigDecimal qty = decimalOrNull(body, "qty");
        BigDecimal limitPrice = decimalOrNull(body, "limit_price");
        BigDecimal stopPrice = decimalOrNull(body, "stop_price");
        BigDecimal takeProfit = decimalOrNull(body, "take_profit");

        ExecutorSignal signal = signalRepo.findById(signalId);
        if (signal == null) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, bodySymbol, false,
                    RejectReason.SCHEMA_INVALID.name(), List.of("SIGNAL_NOT_FOUND"),
                    "signal not found for id " + signalId, null, runId, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", RejectReason.SCHEMA_INVALID.name())));
        }

        if (!"PENDING".equals(signal.status())) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    RejectReason.DUPLICATE.name(), List.of("ALREADY_PROCESSED:" + signal.status()),
                    "signal already processed, status=" + signal.status(), null, runId, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", RejectReason.DUPLICATE.name())));
        }

        VetoService.Outcome veto = vetoService.evaluate(signal, positionRepo.countOpen(),
                minConfidence, maxPositions);
        List<String> vetoTrace = new ArrayList<>();
        for (VetoResult r : veto.results()) {
            vetoTrace.add(r.check() + ":" + (r.passed() ? "PASS" : "FAIL"));
        }

        if (!veto.passed()) {
            String reason = veto.firstFailure().name();
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, vetoTrace, "rejected by veto: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason, "veto_trace", vetoTrace)));
        }

        BigDecimal referencePrice = signal.referencePrice() != null
                ? signal.referencePrice() : limitPrice;
        // Invariant: both connectionEnv and allowedConnection are the same server-fixed
        // config connection. place-entry deliberately ignores any body-supplied connection
        // and always trades on the guarded config default, so NON_SIM_CONNECTION cannot fire
        // through this controller today. Primary live-trading safety is the non-live Agora
        // trading token (saxo-live is physically unreachable). The guard's connection arm
        // becomes load-bearing only if per-request connection routing is added in a later slice.
        OrderGuard.Result guard = orderGuard.check(side, qty, referencePrice, stopPrice,
                connection, connection);

        if (!guard.ok()) {
            String reason = guard.reason().name();
            List<String> trace = new ArrayList<>(vetoTrace);
            trace.add("ORDER_GUARD:" + reason);
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, trace, "rejected by order guard: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason)));
        }

        try {
            BracketRequest req = new BracketRequest(signal.symbol(), side, qty, limitPrice,
                    stopPrice, takeProfit, signalId, null);
            PlacedBracket placed = gateway.placeBracket(connection, req);
            String brokerOrderId = placed.bracketId();
            String stopOrderId = placed.stopLegId();

            long positionId = positionRepo.insert(new ExecutorPosition(null, connection,
                    signal.symbol(), side, qty, referencePrice, stopPrice, stopPrice, 1,
                    null, signal.killCriteria(), signalId, signal.source(), null, null,
                    "OPEN", brokerOrderId,
                    referencePrice, null, 0, null, null, null, null, stopOrderId));

            signalRepo.markStatus(signalId, "ACCEPTED");
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), true,
                    null, vetoTrace, "entry placed", brokerOrderId, runId, null));

            return ResponseEntity.ok(Map.of("output", Map.of(
                    "placed", true,
                    "broker_order_id", brokerOrderId,
                    "position_id", positionId)));
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    "BROKER_ERROR", vetoTrace, "broker call failed: " + e.getMessage(),
                    null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "BROKER_ERROR", "error", e.getMessage())));
        }
    }

    private BigDecimal decimalOrNull(JsonNode body, String field) {
        JsonNode v = body.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        return new BigDecimal(v.asString());
    }

    // -------------------------------------------------------------------
    // submit-decision
    // -------------------------------------------------------------------

    @PostMapping("/tools/submit-decision")
    public ResponseEntity<Map<String, Object>> submitDecision(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        if (body == null) body = mapper.createObjectNode();

        int recorded = 0;
        JsonNode decisions = body.path("decisions");
        if (decisions.isArray()) {
            for (JsonNode d : decisions) {
                String action = d.path("action").asString("");
                if (!"SKIP".equals(action)) continue;

                String signalId = d.path("signal_id").asString("");
                // NOTE (slice-2): symbol is trusted from the request body and not cross-checked
                // against the stored signal's actual symbol — deferred per final review item #5.
                String symbol = d.path("symbol").asString("");
                String rationale = d.path("rationale").asString(null);

                decisionRepo.insert(new ExecutorDecision(null, signalId, symbol, false,
                        null, List.of(), rationale, null, runId, null));
                signalRepo.markStatus(signalId, "SKIPPED");
                recorded++;
            }
        }
        return ResponseEntity.ok(Map.of("output", Map.of("recorded", recorded)));
    }

    // -------------------------------------------------------------------
    // complete
    // -------------------------------------------------------------------

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        return ResponseEntity.noContent().build();
    }
}
