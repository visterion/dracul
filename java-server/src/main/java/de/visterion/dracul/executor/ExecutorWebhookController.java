package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 7 tool webhooks + completion callback for the Dracul executor agent.
 *
 * <p>{@code place-entry} is the safety-critical core: the LLM only <em>requests</em> an
 * entry, code decides. Every rejection path (schema, veto, order guard, broker error)
 * short-circuits before any call to {@link ExecutionGateway#placeBracket}.
 *
 * <p>{@code exit-position} is the mirror-image LLM tool for maintenance (SOFT full exit):
 * exits are always permitted (reducing risk needs no veto), and it books the close, sets a
 * cooldown, and writes a {@code SOFT_TRIGGER}/{@code EXIT_FULL} decision-log row — mirroring
 * {@link HardTriggerService}'s idiom for gateway/repo wiring and decision-log construction.
 */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor")
public class ExecutorWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ExecutorWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final ExecutorSignalRepository signalRepo;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorDecisionRepository decisionRepo;
    private final VetoService vetoService;
    private final OrderGuard orderGuard;
    private final ExecutionGateway gateway;
    private final ExecutorIndicators executorIndicators;
    private final MaintenancePipeline pipeline;
    private final DecisionLogRepository decisionLogRepo;
    private final CooldownRepository cooldownRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final EntryContextAssembler assembler;
    private final PositionSizer sizer;
    private final SignalRanker ranker;
    private final VetoConfig vetoConfig;
    private final Tranche2Detector tranche2Detector;
    private final TelegramNotifier telegram;

    /**
     * Wide default take-profit distance, in R (risk units = |entry - stop|). Agora's
     * {@code place_bracket} requires a take-profit leg, so a target must always be present or the
     * order is rejected with {@code missing required argument: takeProfitLimit}. The strategy's
     * real exits are the trailing chandelier / giveback stops, not a fixed target — so this 3R
     * default is intentionally wide and rarely fills; it exists only to make the bracket valid.
     */
    private static final BigDecimal DEFAULT_TARGET_R = new BigDecimal("3.0");

    private final String connection;
    private final double minConfidence;
    private final int maxPositions;
    private final int atrPeriod;
    private final int swingPeriod;
    private final int cooldownDays;
    private final int maxTranche;

    public ExecutorWebhookController(
            ExecutorSignalRepository signalRepo,
            ExecutorPositionRepository positionRepo,
            ExecutorDecisionRepository decisionRepo,
            VetoService vetoService,
            OrderGuard orderGuard,
            ExecutionGateway gateway,
            ExecutorIndicators executorIndicators,
            MaintenancePipeline pipeline,
            DecisionLogRepository decisionLogRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            EntryContextAssembler assembler,
            PositionSizer sizer,
            SignalRanker ranker,
            Tranche2Detector tranche2Detector,
            TelegramNotifier telegram,
            @Value("${dracul.executor.webhook-token:}") String webhookToken,
            @Value("${dracul.executor.connection:saxo-sim}") String connection,
            @Value("${dracul.executor.min-confidence:0.65}") double minConfidence,
            @Value("${dracul.executor.max-positions:5}") int maxPositions,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod,
            @Value("${dracul.executor.cooldown-days:10}") int cooldownDays,
            @Value("${dracul.executor.total-budget:10000}") java.math.BigDecimal totalBudget,
            @Value("${dracul.executor.tranche-count:10}") int trancheCount,
            @Value("${dracul.executor.heat-pct:0.06}") double heatPct,
            @Value("${dracul.executor.max-per-sector:2}") int maxPerSector,
            @Value("${dracul.executor.min-price:5}") java.math.BigDecimal minPrice,
            @Value("${dracul.executor.adv-multiple:200}") int advMultiple,
            @Value("${dracul.executor.max-signal-age-days:5}") int maxSignalAgeDays,
            @Value("${dracul.executor.chase-atr-mult:1.0}") double chaseAtrMult,
            @Value("${dracul.executor.pace-per-week:2}") int pacePerWeek,
            @Value("${dracul.executor.max-tranche:2}") int maxTranche) {

        this.signalRepo = signalRepo;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.vetoService = vetoService;
        this.orderGuard = orderGuard;
        this.gateway = gateway;
        this.executorIndicators = executorIndicators;
        this.pipeline = pipeline;
        this.decisionLogRepo = decisionLogRepo;
        this.cooldownRepo = cooldownRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.clock = Clock.systemUTC();
        this.connection = connection;
        this.minConfidence = minConfidence;
        this.maxPositions = maxPositions;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
        this.cooldownDays = cooldownDays;
        this.maxTranche = maxTranche;
        this.verifier = new BearerTokenVerifier(webhookToken);
        this.assembler = assembler;
        this.sizer = sizer;
        this.ranker = ranker;
        this.tranche2Detector = tranche2Detector;
        this.telegram = telegram;
        this.vetoConfig = new VetoConfig(minConfidence, maxPositions, totalBudget, heatPct,
                maxPerSector, minPrice, advMultiple, maxSignalAgeDays, chaseAtrMult, pacePerWeek,
                trancheCount);
    }

    // -------------------------------------------------------------------
    // fetch-pending-signals
    // -------------------------------------------------------------------

    @PostMapping("/tools/fetch-pending-signals")
    public ResponseEntity<Map<String, Object>> fetchPendingSignals(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        List<ExecutorPosition> openPositions = positionRepo.findOpen();
        Map<String, String> openMechanisms = SignalRanker.openMechanisms(openPositions, signalRepo);
        List<ExecutorSignal> ranked = ranker.rank(signalRepo.findPending(50), openPositions, openMechanisms);

        List<Map<String, Object>> signals = new ArrayList<>();
        for (ExecutorSignal s : ranked) {
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

        String conn = resolveConnection(inputOf(body));
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

        String conn = resolveConnection(inputOf(body));
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

    /**
     * Unwrap the tool-argument object from Vistierie's webhook envelope.
     *
     * <p>{@code ToolDispatcher.callOnce} POSTs every http-tool call with the shape
     * {@code {"run_id":..., "tool_name":..., "input":{...the LLM's arguments...}}}, so the
     * actual tool arguments live under {@code input}. When {@code input} is present and an
     * object, this returns it; otherwise it falls back to {@code body} itself so direct curl
     * calls (and top-level-args tests) that pass arguments at the top level keep working.
     */
    private static JsonNode inputOf(JsonNode body) {
        return (body != null && body.path("input").isObject()) ? body.path("input") : body;
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
        JsonNode input = inputOf(body);

        String signalId = input.path("signal_id").asString("");
        String bodySymbol = input.path("symbol").asString("");
        String side = input.path("side").asString(null);
        BigDecimal limitPrice = decimalOrNull(input, "limit_price");
        BigDecimal stopPrice = decimalOrNull(input, "stop_price");
        BigDecimal takeProfit = decimalOrNull(input, "take_profit");

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

        // "side" is a controller-level tool argument, not part of the ExecutorSignal, so
        // VetoService.evaluate never validates it. Reject malformed/missing side before any
        // sizing math (the sizer has no null guards and silently treats non-BUY as SELL).
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    RejectReason.SCHEMA_INVALID.name(), List.of("INVALID_SIDE:" + side),
                    "side must be BUY or SELL, got " + side, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", RejectReason.SCHEMA_INVALID.name())));
        }

        EntryContext ctx = assembler.assemble(signal);

        // Single order-price basis for all order mechanics (sizing, guard, take-profit, booking):
        // the LLM's limit price when given, otherwise the freshly assembled current close. This
        // replaces the old signal.referencePrice() cascade, which could be up to
        // maxSignalAgeDays stale and diverge from the sizer's price basis (ctx.price()).
        //
        // When mandatory upstream data is missing, VetoService.evaluate short-circuits on the
        // DATA_UNAVAILABLE pre-veto before ever reading `sizing` or `orderPrice` — so a
        // zero/placeholder Sizing and a null orderPrice are safe here and avoid dereferencing
        // ctx.price() (only guaranteed non-null when ctx.missing() is empty) or calling the sizer
        // (which has no null guards) with absent inputs.
        BigDecimal orderPrice;
        Sizing sizing;
        if (ctx.missing() == null || ctx.missing().isEmpty()) {
            orderPrice = limitPrice != null ? limitPrice : ctx.price();
            sizing = sizer.size(side, orderPrice, ctx.atr(), ctx.swingLow(), stopPrice,
                    ctx.trancheAmount(), ctx.fxToAccount());
        } else {
            orderPrice = null;
            sizing = new Sizing(BigDecimal.ZERO, null, BigDecimal.ZERO, null, null, false);
        }

        VetoService.Outcome veto = vetoService.evaluate(signal, ctx, sizing, vetoConfig);
        List<String> vetoTrace = new ArrayList<>();
        for (VetoResult r : veto.results()) {
            vetoTrace.add(r.check() + ":" + (r.passed() ? "PASS" : "FAIL"));
        }

        if (!veto.passed()) {
            String reason = veto.firstFailure().name();
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, vetoTrace, "rejected by veto: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");

            if (veto.firstFailure() == RejectReason.CONTRADICTION
                    && veto.contradictingSignalId() != null) {
                String otherId = veto.contradictingSignalId();
                signalRepo.markStatus(otherId, "REJECTED");
                decisionRepo.insert(new ExecutorDecision(null, otherId, signal.symbol(), false,
                        reason, vetoTrace, "contradiction pair with " + signalId, null, runId, null));
            }

            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason, "veto_trace", vetoTrace)));
        }

        if (sizing.qty() == null || sizing.qty().signum() == 0) {
            String reason = RejectReason.TRANCHE_TOO_SMALL.name();
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, vetoTrace, "rejected: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason, "veto_trace", vetoTrace)));
        }

        BigDecimal qty = sizing.qty();
        // Invariant: both connectionEnv and allowedConnection are the same server-fixed
        // config connection. place-entry deliberately ignores any body-supplied connection
        // and always trades on the guarded config default, so NON_SIM_CONNECTION cannot fire
        // through this controller today. Primary live-trading safety is the non-live Agora
        // trading token (saxo-live is physically unreachable). The guard's connection arm
        // becomes load-bearing only if per-request connection routing is added in a later slice.
        OrderGuard.Result guard = orderGuard.check(side, qty, orderPrice, stopPrice,
                sizing.stopMin(), sizing.stopMax(), connection, connection);

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

        // Guarantee a take-profit leg. Agora's place_bracket rejects any bracket without one, but
        // this strategy exits via the trailing chandelier, not a fixed target — so when the LLM
        // omits take_profit we synthesize a wide DEFAULT_TARGET_R (3R) target that rarely fills. An
        // explicit LLM take_profit always wins (only fill when null). If we can't compute R (no
        // order price or stop price) leave it null and let the existing broker-error path handle it.
        if (takeProfit == null && orderPrice != null && stopPrice != null) {
            BigDecimal r = orderPrice.subtract(stopPrice).abs();
            if (r.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal offset = DEFAULT_TARGET_R.multiply(r);
                BigDecimal target = "SELL".equals(side)
                        ? orderPrice.subtract(offset)
                        : orderPrice.add(offset);
                takeProfit = target.setScale(2, RoundingMode.HALF_UP);
            }
        }

        PlacedBracket placed;
        try {
            BracketRequest req = new BracketRequest(signal.symbol(), side, qty, limitPrice,
                    stopPrice, takeProfit, signalId, null);
            placed = gateway.placeBracket(connection, req);
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    "BROKER_ERROR", vetoTrace, "broker call failed: " + e.getMessage(),
                    null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "BROKER_ERROR", "error", e.getMessage())));
        }

        String brokerOrderId = placed.bracketId();
        String stopOrderId = placed.stopLegId();

        try {
            long positionId = positionRepo.insert(new ExecutorPosition(null, connection,
                    signal.symbol(), side, qty, orderPrice, stopPrice, stopPrice, 1,
                    null, signal.killCriteria(), signalId, signal.source(), null, null,
                    "OPEN", brokerOrderId,
                    orderPrice, null, 0, null, null, null, null, stopOrderId,
                    ctx.candidateSector(), ctx.dayHigh(), null, null, 0, null, null));

            signalRepo.markStatus(signalId, "ACCEPTED");

            try {
                decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), true,
                        null, vetoTrace, "entry placed", brokerOrderId, runId, null));
            } catch (RuntimeException e) {
                // Position and signal status are durably persisted — the order is managed.
                // Only the accepted-audit row is missing; log it, but do not flip the response
                // into a false ORPHANED_ORDER (that would contradict persisted state).
                log.error("accepted-audit decisionRepo.insert failed for signal {} position {} "
                                + "broker order {}: {}",
                        signalId, positionId, brokerOrderId, e.getMessage(), e);
            }

            return ResponseEntity.ok(Map.of("output", Map.of(
                    "placed", true,
                    "broker_order_id", brokerOrderId,
                    "position_id", positionId)));
        } catch (RuntimeException e) {
            // Broker holds a LIVE order but the book write failed. Alert FIRST — the DB
            // may be the failing component, so Telegram is the only reliable channel.
            telegram.notifyAlert(signal.symbol(), "ORPHANED_ORDER", "CRITICAL",
                    "broker order " + brokerOrderId + " placed but book write failed: " + e.getMessage()
                            + " — reconcile orphan scan will re-flag until resolved");
            try {
                decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                        "ORPHANED_ORDER", vetoTrace,
                        "broker order " + brokerOrderId + " live but persistence failed: " + e.getMessage(),
                        brokerOrderId, runId, null));
            } catch (RuntimeException ignored) {
                // same DB is likely down; the Telegram alert above is the escalation of record
            }
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "ORPHANED_ORDER", "broker_order_id", brokerOrderId)));
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
        JsonNode decisions = inputOf(body).path("decisions");
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
    // fetch-open-positions
    // -------------------------------------------------------------------

    @PostMapping("/tools/fetch-open-positions")
    public ResponseEntity<Map<String, Object>> fetchOpenPositions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        List<EnrichedPosition> positions = pipeline.run(connection, runId);
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (EnrichedPosition p : positions) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("symbol", p.symbol());
            node.put("signal_id", p.sourceSignalId());
            node.put("side", p.side());
            node.put("qty", p.qty());
            node.put("entry_price", p.entryPrice());
            node.put("active_stop", p.activeStop());
            node.put("current_price", p.currentPrice());
            node.put("atr", p.atr());
            node.put("chandelier_level", p.chandelierLevel());
            node.put("r_current", p.rCurrent());
            node.put("mfe_r", p.mfeR());
            node.put("days_held", p.daysHeld());
            node.put("kill_criteria", p.killCriteria());

            Map<String, Object> softTrigger = new LinkedHashMap<>();
            softTrigger.put("chandelier_breach", p.chandelierBreach());
            softTrigger.put("ma_break", p.maBreak());
            softTrigger.put("confirm_count", p.softConfirmCount());
            softTrigger.put("kill_criteria_breached", p.killCriteriaBreached());
            node.put("soft_trigger", softTrigger);

            Map<String, Object> tranche2 = new LinkedHashMap<>();
            tranche2.put("eligible", p.tranche2Eligible());
            tranche2.put("reason", p.tranche2Reason());
            node.put("tranche2", tranche2);

            serialized.add(node);
        }
        return ResponseEntity.ok(Map.of("output", Map.of("positions", serialized)));
    }

    // -------------------------------------------------------------------
    // exit-position — LLM SOFT full exit; exits are always permitted, no veto
    // -------------------------------------------------------------------

    @PostMapping("/tools/exit-position")
    public ResponseEntity<Map<String, Object>> exitPosition(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        if (body == null) body = mapper.createObjectNode();
        JsonNode input = inputOf(body);

        String symbol = input.path("symbol").asString("");
        String reason = input.path("reason").asString("SOFT_EXIT");
        Double confidence = input.path("confidence").isNumber() ? input.path("confidence").asDouble() : null;
        String reasoning = input.path("reasoning").asString(null);

        ExecutorPosition position = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .filter(p -> symbol.equals(p.symbol()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "reason", "NO_OPEN_POSITION")));
        }

        CloseResult cr;
        try {
            cr = gateway.flatten(connection, symbol, BigDecimal.ONE);
        } catch (BrokerUnavailableException e) {
            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, null, null,
                    "ESCALATE", "BROKER_UNAVAILABLE", null,
                    "broker unavailable during soft-exit flatten: " + e.getMessage(),
                    confidence, null, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "reason", "BROKER_ERROR")));
        }

        BigDecimal exitPrice = cr.avgFillPrice();
        BigDecimal realizedR = exitPrice != null ? computeR(position, exitPrice) : null;

        positionRepo.close(position.id(), exitPrice, realizedR, reason);
        cooldownRepo.add(symbol, reason, clock.instant().plus(Duration.ofDays(cooldownDays)),
                "fresh setup only");

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("exit_price", exitPrice);
        inputs.put("realized_r", realizedR);
        inputs.put("active_stop", position.activeStop());

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", 1.0);

        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "SOFT_TRIGGER", null, null, null, symbol, inputs, null,
                "EXIT_FULL", reason, orderJson, reasoning, confidence, null, null));

        return ResponseEntity.ok(Map.of("output",
                Map.of("exited", true, "exit_reason", reason)));
    }

    private BigDecimal computeR(ExecutorPosition p, BigDecimal exitPrice) {
        BigDecimal numerator;
        BigDecimal denominator;
        if ("SELL".equals(p.side())) {
            numerator = p.entryPrice().subtract(exitPrice);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = exitPrice.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------
    // add-tranche — code-verified tranche-2 adds to an open tranche-1 position
    // -------------------------------------------------------------------

    @PostMapping("/tools/add-tranche")
    public ResponseEntity<Map<String, Object>> addTranche(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        if (body == null) body = mapper.createObjectNode();
        JsonNode input = inputOf(body);

        String symbol = input.path("symbol").asString("");

        ExecutorPosition position = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .filter(p -> symbol.equals(p.symbol()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            String reason = RejectReason.NO_POSITION.name();
            decisionRepo.insert(new ExecutorDecision(null, null, symbol, false,
                    reason, List.of(), "no open position for " + symbol, null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        if (position.tranche() >= maxTranche) {
            String reason = RejectReason.MAX_TRANCHE.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "tranche cap reached: " + position.tranche() + "/" + maxTranche,
                    null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        EntryContext ctx = assembler.assembleForSymbol(symbol);
        if (ctx.missing() != null && !ctx.missing().isEmpty()) {
            String reason = RejectReason.DATA_UNAVAILABLE.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, ctx.missing(), "data unavailable: " + ctx.missing(), null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        String positionMechanism = resolvePositionMechanism(position.sourceSignalId());
        Tranche2Detector.Tranche2Status t2 = tranche2Detector.detect(position, ctx.price(),
                ctx.pendingSignals(), positionMechanism);
        if (!t2.eligible()) {
            String reason = RejectReason.NOT_ELIGIBLE.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "tranche 2 not eligible", null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        // Tranche-2 sizing reuses the position's EXISTING active stop — it predates this add and
        // is never re-derived from the *current* ATR/swing levels, so PositionSizer.stopInWindow()
        // (which validates freshness against those current levels) is deliberately ignored here;
        // only qty/risk outputs are used.
        Sizing sizing = sizer.size(position.side(), ctx.price(), ctx.atr(), ctx.swingLow(),
                position.activeStop(), ctx.trancheAmount(), ctx.fxToAccount());

        if (sizing.qty() == null || sizing.qty().compareTo(BigDecimal.ONE) < 0) {
            String reason = RejectReason.TRANCHE_TOO_SMALL.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "rejected: " + reason, null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        // Shares CapitalBounds with VetoService's BUDGET/HEAT_LIMIT vetos (5/6): a tranche-sized
        // slice of the account must fit within both remaining cash and remaining total-budget
        // headroom, and the new risk must not push open heat past its ceiling.
        CapitalBounds.Result bounds = CapitalBounds.check(ctx.account(), ctx.openExposure(),
                ctx.openHeat(), sizing.newRiskAccountCcy(), vetoConfig.totalBudget(),
                vetoConfig.trancheCount(), vetoConfig.heatPct());

        if (!bounds.heatOk()) {
            String reason = RejectReason.HEAT_LIMIT.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "rejected: " + reason, null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        if (!bounds.budgetOk()) {
            String reason = RejectReason.BUDGET.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "rejected: " + reason, null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        BigDecimal orderPrice = ctx.price();
        BigDecimal stopPrice = position.activeStop();

        // Guarantee a take-profit leg, same 3R-from-order-price synthesis as place-entry.
        BigDecimal takeProfit = null;
        if (orderPrice != null && stopPrice != null) {
            BigDecimal r = orderPrice.subtract(stopPrice).abs();
            if (r.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal offset = DEFAULT_TARGET_R.multiply(r);
                BigDecimal target = "SELL".equals(position.side())
                        ? orderPrice.subtract(offset)
                        : orderPrice.add(offset);
                takeProfit = target.setScale(2, RoundingMode.HALF_UP);
            }
        }

        PlacedBracket placed;
        try {
            String clientRef = "t2-" + (position.sourceSignalId() != null
                    ? position.sourceSignalId() : "pos-" + position.id());
            BracketRequest req = new BracketRequest(symbol, position.side(), sizing.qty(), orderPrice,
                    stopPrice, takeProfit, clientRef, null);
            placed = gateway.placeBracket(connection, req);
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    "BROKER_ERROR", List.of(), "broker call failed: " + e.getMessage(), null, runId, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "BROKER_ERROR", "error", e.getMessage())));
        }

        String brokerOrderId = placed.bracketId();

        try {
            BigDecimal newQty = position.qty().add(sizing.qty());
            BigDecimal newEntry = position.qty().multiply(position.entryPrice())
                    .add(sizing.qty().multiply(orderPrice))
                    .divide(newQty, 6, RoundingMode.HALF_UP);

            positionRepo.updateTranche2(position.id(), newQty, newEntry, brokerOrderId, placed.stopLegId());

            try {
                decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, true,
                        null, List.of(), "tranche 2 added: " + t2.reason(), brokerOrderId, runId, null));
            } catch (RuntimeException e) {
                // Position tranche update is durably persisted — the order is managed. Only the
                // accepted-audit row is missing; log it, but do not flip the response into a
                // false ORPHANED_ORDER (that would contradict persisted state).
                log.error("accepted-audit decisionRepo.insert failed for signal {} position {} "
                                + "broker order {}: {}",
                        position.sourceSignalId(), position.id(), brokerOrderId, e.getMessage(), e);
            }

            return ResponseEntity.ok(Map.of("output", Map.of(
                    "placed", true,
                    "qty", sizing.qty(),
                    "reason", t2.reason())));
        } catch (RuntimeException e) {
            // Broker holds a LIVE tranche-2 order but the book write failed. Alert FIRST — the
            // DB may be the failing component, so Telegram is the only reliable channel.
            telegram.notifyAlert(symbol, "ORPHANED_ORDER", "CRITICAL",
                    "tranche-2 order " + brokerOrderId + " placed but book write failed: " + e.getMessage()
                            + " — reconcile orphan scan will re-flag until resolved");
            try {
                decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                        "ORPHANED_ORDER", List.of(),
                        "tranche-2 order " + brokerOrderId + " live but persistence failed: " + e.getMessage(),
                        brokerOrderId, runId, null));
            } catch (RuntimeException ignored) {
                // same DB is likely down; the Telegram alert above is the escalation of record
            }
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "ORPHANED_ORDER", "broker_order_id", brokerOrderId)));
        }
    }

    /** Same 4-line null-safe lookup as {@code MaintenancePipeline.resolveMechanism} — replicated
     *  here rather than shared because that method is private to the maintenance pipeline. */
    private String resolvePositionMechanism(String sourceSignalId) {
        if (sourceSignalId == null) return null;
        ExecutorSignal source = signalRepo.findById(sourceSignalId);
        return source == null ? null : source.mechanism();
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
