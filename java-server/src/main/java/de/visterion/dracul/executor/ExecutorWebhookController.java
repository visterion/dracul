package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.executor.broker.RestoredLeg;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.position.PositionContextRepository;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Agora's reject code for the definite "there is no open position to flatten" verdict —
     *  the same typed field {@code AgoraExecutionGateway.requireAccepted} threads through for
     *  {@code LEG_NOT_FOUND} (see {@code StopRatchetService}). Structural, not transient: no
     *  retry brings a gone position back, and it must not be filed as
     *  {@code BROKER_UNAVAILABLE} — that is exactly the 2026-08-24 RGNX incident, where the
     *  broker had long stopped the position out but the book still held it OPEN.
     *
     *  <p>Deliberately NOT {@code "NOT_FOUND"} (fix round 2): Agora's {@code FlattenTool} emits
     *  the generic {@code NOT_FOUND} for an HTTP 404 reached elsewhere inside a flatten call (a
     *  related-orders lookup, the closing POST itself on a partial close) — that says nothing
     *  about whether the position exists. {@code NO_POSITION} is the narrower code Agora reserves
     *  for the one definite determination. A plain {@code NOT_FOUND} rejection still falls
     *  through to the generic branch below, named by its own raw reject code — a verdict,
     *  honestly not claimed to mean the position is gone.
     *
     *  <p>Named {@code AGORA_NO_POSITION}, not just {@code NO_POSITION}: {@link RejectReason}
     *  already declares a {@code NO_POSITION} value in this very class (see
     *  {@link RejectReason#NO_POSITION}, used a few hundred lines below), meaning DRACUL's own
     *  book has no matching open position (the entry/add-tranche path) — a completely different
     *  check, on a completely different data source, from this field's agora wire code. One
     *  literal spelling, two meanings, must not share one name in the same file. */
    private static final String AGORA_NO_POSITION = "NO_POSITION";

    private final BearerTokenVerifier verifier;
    private final ExecutorSignalRepository signalRepo;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorPositionLegRepository legRepo;
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
    private final ExecutorNotifier executorNotifier;
    private final PositionContextRepository positionContextRepo;
    private final PatternRepository patternRepo;

    private final String connection;
    private final double minConfidence;
    private final int maxPositions;
    private final int atrPeriod;
    private final int swingPeriod;
    private final int cooldownDays;
    private final int maxTranche;
    private final int entryGtdDays;
    private final int maxBrokerAttempts;
    /** Length of the rolling window over which failed runs are counted for the attempt cap. */
    private final int brokerAttemptWindowHours;
    /** Broker calls per signal allowed inside a single run before the throttle bites. */
    private final int maxBrokerCallsPerRun;
    /** How many ATRs the protective leg rests away from the logical stop; 0 = exact legacy. */
    private final BigDecimal brokerStopBufferAtr;
    /** Proximity band the broker accepts for a bracket leg, as a fraction of the entry price. */
    private final BigDecimal maxBrokerStopPct;
    /** Fraction of the total budget a single tranche may lose at its logical stop. */
    private final double riskPct;
    /** Period of the short ATR window, for the {@code stop_basis} audit label. */
    private final int atrShortPeriod;

    @Autowired
    public ExecutorWebhookController(
            ExecutorSignalRepository signalRepo,
            ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo,
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
            ExecutorNotifier executorNotifier,
            PositionContextRepository positionContextRepo,
            PatternRepository patternRepo,
            @Value("${dracul.executor.webhook-token:}") String webhookToken,
            @Value("${dracul.executor.connection:depot-1}") String connection,
            @Value("${dracul.executor.min-confidence:0.40}") double minConfidence,
            @Value("${dracul.executor.max-positions:8}") int maxPositions,
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
            @Value("${dracul.executor.drift-anchor-atr-mult:0.0}") double driftAnchorAtrMult,
            @Value("${dracul.executor.value-anchor-atr-mult:3.0}") double valueAnchorAtrMult,
            @Value("${dracul.executor.pace-per-week:2}") int pacePerWeek,
            @Value("${dracul.executor.max-tranche:2}") int maxTranche,
            @Value("${dracul.executor.entry-gtd-days:2}") int entryGtdDays,
            @Value("${dracul.executor.max-broker-attempts:3}") int maxBrokerAttempts,
            @Value("${dracul.executor.broker-attempt-window-hours:72}") int brokerAttemptWindowHours,
            @Value("${dracul.executor.max-broker-calls-per-run:2}") int maxBrokerCallsPerRun,
            @Value("${dracul.executor.instrument-currency:USD}") String instrumentCurrency,
            @Value("${dracul.executor.broker-stop-buffer-atr:1.0}") java.math.BigDecimal brokerStopBufferAtr,
            @Value("${dracul.executor.max-broker-stop-pct:0.20}") java.math.BigDecimal maxBrokerStopPct,
            @Value("${dracul.executor.risk-pct:0.01}") double riskPct,
            @Value("${dracul.executor.atr-short-period:5}") int atrShortPeriod,
            MechanismBudget mechanismBudget) {
        this(signalRepo, positionRepo, legRepo, decisionRepo, vetoService, orderGuard, gateway, executorIndicators,
                pipeline, decisionLogRepo, cooldownRepo, ruleVersions, mapper, assembler, sizer, ranker,
                tranche2Detector, telegram, executorNotifier, positionContextRepo, patternRepo, webhookToken, connection, minConfidence,
                maxPositions, atrPeriod, swingPeriod, cooldownDays, totalBudget, trancheCount, heatPct,
                maxPerSector, minPrice, advMultiple, maxSignalAgeDays, chaseAtrMult, pacePerWeek, maxTranche,
                entryGtdDays, maxBrokerAttempts, brokerAttemptWindowHours, maxBrokerCallsPerRun,
                driftAnchorAtrMult, valueAnchorAtrMult, instrumentCurrency,
                brokerStopBufferAtr, maxBrokerStopPct, riskPct, atrShortPeriod, mechanismBudget,
                Clock.systemUTC());
    }

    /** Package-private overload with an injectable {@link Clock}, so tests can assert
     *  deterministic {@code latency.signal_to_decision_seconds} values. */
    ExecutorWebhookController(
            ExecutorSignalRepository signalRepo,
            ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo,
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
            ExecutorNotifier executorNotifier,
            PositionContextRepository positionContextRepo,
            PatternRepository patternRepo,
            String webhookToken,
            String connection,
            double minConfidence,
            int maxPositions,
            int atrPeriod,
            int swingPeriod,
            int cooldownDays,
            java.math.BigDecimal totalBudget,
            int trancheCount,
            double heatPct,
            int maxPerSector,
            java.math.BigDecimal minPrice,
            int advMultiple,
            int maxSignalAgeDays,
            double chaseAtrMult,
            int pacePerWeek,
            int maxTranche,
            int entryGtdDays,
            int maxBrokerAttempts,
            int brokerAttemptWindowHours,
            int maxBrokerCallsPerRun,
            double driftAnchorAtrMult,
            double valueAnchorAtrMult,
            String instrumentCurrency,
            java.math.BigDecimal brokerStopBufferAtr,
            java.math.BigDecimal maxBrokerStopPct,
            double riskPct,
            int atrShortPeriod,
            MechanismBudget mechanismBudget,
            Clock clock) {

        this.signalRepo = signalRepo;
        this.positionRepo = positionRepo;
        this.legRepo = legRepo;
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
        this.clock = clock;
        this.connection = connection;
        this.minConfidence = minConfidence;
        this.maxPositions = maxPositions;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
        this.cooldownDays = cooldownDays;
        this.maxTranche = maxTranche;
        this.entryGtdDays = entryGtdDays;
        this.maxBrokerAttempts = maxBrokerAttempts;
        this.brokerAttemptWindowHours = brokerAttemptWindowHours;
        this.maxBrokerCallsPerRun = maxBrokerCallsPerRun;
        this.brokerStopBufferAtr = brokerStopBufferAtr;
        this.maxBrokerStopPct = maxBrokerStopPct;
        this.riskPct = riskPct;
        this.atrShortPeriod = atrShortPeriod;
        this.verifier = new BearerTokenVerifier(webhookToken);
        this.assembler = assembler;
        this.sizer = sizer;
        this.ranker = ranker;
        this.tranche2Detector = tranche2Detector;
        this.telegram = telegram;
        this.executorNotifier = executorNotifier;
        this.positionContextRepo = positionContextRepo;
        this.patternRepo = patternRepo;
        this.vetoConfig = new VetoConfig(minConfidence, maxPositions, totalBudget, heatPct,
                maxPerSector, minPrice, advMultiple, maxSignalAgeDays, chaseAtrMult, pacePerWeek,
                trancheCount, driftAnchorAtrMult, valueAnchorAtrMult, instrumentCurrency, mechanismBudget);
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
            node.put("mechanism", s.mechanism());
            node.put("kill_criteria", s.killCriteria());
            node.put("horizon", s.horizon());

            ExecutorIndicators.Levels levels = executorIndicators.levels(s.symbol(), atrPeriod, swingPeriod);
            if (levels.available()) {
                node.put("atr", levels.atr());
                node.put("swing_low", levels.swingLow());
                node.put("reference_price", levels.referencePrice());

                // The WINDOW the LLM proposes a stop inside comes from atrEff -- the post-report
                // window, when there is one. The `atr` field above deliberately stays ATR22: the
                // LLM reasons about the volatility number it has always seen, and the window is
                // where the widening belongs.
                StopWindow w = sizer.stopWindow(s.direction(), levels.referencePrice(),
                        levels.atrEff(), levels.swingLow());
                node.put("stop_min", w.stopMin());
                node.put("stop_max", w.stopMax());
            } else {
                node.put("atr", null);
                node.put("swing_low", null);
                node.put("reference_price", s.referencePrice());
                node.put("stop_min", null);
                node.put("stop_max", null);
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

    /**
     * Recovers a decision LIST from whatever shape the bridge actually delivered.
     *
     * <p><b>The declared tool input schema is not enforced anywhere.</b> Vistierie validates
     * {@code input_schema} as a schema when an agent definition is written; it never validates a
     * call's arguments against it (the only {@code schemas.validate} call site is
     * {@code OutputSchemaValidator:49}, which checks the agent's OUTPUT). So the shape that
     * arrives is whatever the model and the bridge produced between them, and the bridge
     * stringifies tool arguments — verified in production {@code run_tool_calls}, 2026-08-03
     * 06:11:47:
     * <pre>
     *   jsonb_typeof(input_json->'decisions') = string  ->  {"recorded": 0}
     *   {"decisions": "[{\"signal_id\":\"61bfad16-…\",\"action\":\"SKIP\",…}]"}
     * </pre>
     * The handler gated on {@code isArray()} and answered {@code recorded: 0} without a word, so
     * every SKIP stayed PENDING and was re-evaluated the next run. The same stringification broke
     * the HiveMem {@code where} filter, i.e. it is a property of the bridge rather than a one-off
     * of this tool.
     *
     * @return the decisions as an array node; an EMPTY array when the argument was absent (that
     *         is a model with nothing to record, not an error); {@code null} when the argument was
     *         present but could not be read as a decision list — which the caller must report
     *         loudly rather than swallow.
     */
    private JsonNode coerceDecisions(JsonNode decisions) {
        if (decisions == null || decisions.isMissingNode() || decisions.isNull()) {
            return mapper.createArrayNode();
        }
        if (decisions.isArray()) return decisions;
        // A single decision object where an array was declared — the other shape the bridge
        // produces. One decision is still a decision.
        if (decisions.isObject()) return mapper.createArrayNode().add(decisions);
        if (decisions.isTextual()) {
            String text = decisions.stringValue().trim();
            if (text.isEmpty()) return mapper.createArrayNode();
            try {
                // Recurse: double encoding ("\"[{…}]\"") has been observed, and one more pass
                // costs nothing while a second stringification would otherwise lose the run's
                // whole decision set.
                return coerceDecisions(mapper.readTree(text));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Keeps an unusable argument out of the log at full length; it rides into an operator's
     *  eyes, not the model's context. */
    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /**
     * Whether an existing broker order (found via orderByRef on a retry) represents a live order
     * that should be adopted instead of re-placed. A terminal order (CANCELLED/REJECTED) means no
     * real order exists under this clientRef, so it must fall through to normal placement instead
     * of being booked as a phantom position.
     */
    private static boolean isLiveOrder(OrderStatus status) {
        return status == OrderStatus.WORKING || status == OrderStatus.PARTIALLY_FILLED
                || status == OrderStatus.FILLED;
    }

    // -------------------------------------------------------------------
    // rich decision_log construction — inputs snapshot, measured vetos, latency.
    // Mirrors HardTriggerService's decision-log idiom; every place-entry outcome (accept or
    // reject) gets one of these rows, joined later by Task 9's outcome-analytics batch job.
    // -------------------------------------------------------------------

    /** {@code check/passed/measured} for every veto, in evaluation order. */
    private ArrayNode vetoResultsNode(List<VetoResult> results) {
        ArrayNode arr = mapper.createArrayNode();
        for (VetoResult r : results) {
            ObjectNode n = mapper.createObjectNode();
            n.put("check", r.check());
            n.put("passed", r.passed());
            n.put("measured", r.measured());
            arr.add(n);
        }
        return arr;
    }

    /**
     * The full inputs snapshot required by the decision-log spec. Values already computed by
     * {@link VetoService#evaluate} are read from {@code veto.snapshot()} rather than
     * recomputed here; {@code order_price}/{@code atr} come straight from what the controller
     * already has in scope. {@code signal_age_trading_days} uses {@code ctx}'s own {@code -1}
     * "unparseable" sentinel to decide null vs. a real value — never fabricated.
     */
    private ObjectNode inputsSnapshotNode(ExecutorSignal signal, EntryContext ctx, BigDecimal orderPrice,
            BigDecimal orderPriceRounded, VetoService.Outcome veto) {
        ObjectNode n = mapper.createObjectNode();
        // A tranche-2 add has no signal of its own (the source signal is long ACCEPTED) and runs no
        // veto catalog. Explicit nulls, never fabricated values.
        if (signal == null) {
            n.putNull("signal_confidence");
            n.putNull("signal_mechanism");
        } else {
            n.put("signal_confidence", signal.confidence());
            n.put("signal_mechanism", signal.mechanism());
        }
        long ageDays = ctx.signalAgeTradingDays();
        if (ageDays < 0) n.putNull("signal_age_trading_days");
        else n.put("signal_age_trading_days", ageDays);
        n.put("order_price", orderPrice);
        // Tick-rounded price actually submitted (or that would have been submitted) to the
        // broker — distinct from the raw order_price above, which stays the veto/calibration
        // input. Both are needed on reject rows so an analyst can see the raw veto input AND
        // the rounded price side by side (see the BROKER_ERROR/BELOW_ANCHOR production case
        // that motivated this field).
        n.put("submitted_price", orderPriceRounded);
        n.put("atr", ctx.atr());
        n.put("atr_short", ctx.atrShort());
        n.put("atr_effective", ctx.atrEff());
        n.put("book_positions_count", ctx.openPositions() == null ? 0 : ctx.openPositions().size());

        // Ternaries like `snap == null ? (Double) null : snap.heatBeforePct()` are a classic trap:
        // binary numeric promotion forces the null branch through unboxing too, NPEing exactly
        // when snap IS null. Plain if/else avoids it.
        VetoService.Snapshot snap = veto == null ? null : veto.snapshot();
        if (snap == null) {
            n.putNull("portfolio_heat_before_pct");
            n.putNull("portfolio_heat_after_pct");
            n.putNull("budget_free");
            n.putNull("new_positions_this_week");
            n.putNull("sector_count_same");
            n.putNull("cooldown_status");
        } else {
            n.put("portfolio_heat_before_pct", snap.heatBeforePct());
            n.put("portfolio_heat_after_pct", snap.heatAfterPct());
            n.put("budget_free", snap.budgetFree());
            n.put("new_positions_this_week", snap.newPositionsThisWeek());
            n.put("sector_count_same", snap.sectorCountSame());
            n.put("cooldown_status", snap.cooldownStatus());
        }
        return n;
    }

    /** The per-trade risk budget in ACCOUNT currency: a fixed fraction of the total budget. */
    private BigDecimal riskBudgetAccountCcy() {
        return vetoConfig.totalBudget().multiply(BigDecimal.valueOf(riskPct));
    }

    /** Names the ATR window {@code ctx.atrEff()} actually resolved to, for the {@code stop_basis}
     *  audit string. Not cosmetic: {@code stop_basis} is grouped on in the outcome analytics, and a
     *  row that says ATR22 while the stop came from ATR5 mis-attributes the result. */
    private String atrLabel(EntryContext ctx) {
        boolean shortWins = ctx.atrShort() != null && ctx.atr() != null
                && ctx.atrShort().compareTo(ctx.atr()) > 0;
        return "ATR" + (shortWins ? atrShortPeriod : atrPeriod);
    }

    /** {@code qty x (entryPrice - brokerStop) x fx} for a BUY, mirrored for a SELL: the loss the
     *  RESTING LEG permits in a catastrophe. Logged only — heat and the HEAT_LIMIT veto stay on
     *  the logical {@code position_risk}, because on broker risk the same five positions would
     *  occupy ~40 % of the heat limit on day one and make max-positions and heat-pct mutually
     *  unreachable through a TRANSIENT reason that leaves signals silently PENDING. Gap losses
     *  beyond the logical stop are what heat never bounded; they are measured, not capped. */
    private static BigDecimal positionRiskBroker(String side, BigDecimal qty, BigDecimal price,
            BigDecimal brokerStop, BigDecimal fxToAccount) {
        if (qty == null || price == null || brokerStop == null) return null;
        BigDecimal perShare = "BUY".equalsIgnoreCase(side)
                ? price.subtract(brokerStop)
                : brokerStop.subtract(price);
        return qty.multiply(perShare).multiply(fxToAccount)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** {@code latency.signal_to_decision_seconds}, omitted entirely (null) when the signal's
     *  {@code createdAt} is missing or unparseable rather than guessed. */
    private ObjectNode latencyNode(String signalCreatedAt, Instant now) {
        if (signalCreatedAt == null) return null;
        try {
            Instant created = Instant.parse(signalCreatedAt);
            ObjectNode n = mapper.createObjectNode();
            n.put("signal_to_decision_seconds", Duration.between(created, now).getSeconds());
            return n;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Inserts one rich {@code decision_log} row for a place-entry accept or reject.
     *  {@code confidence} is the LLM's own decision confidence (0..1, optional tool argument),
     *  persisted as {@code confidence_in_decision} — the executor-side Brier/calibration input. */
    private void logEntryDecision(String runId, ExecutorSignal signal, EntryContext ctx,
            BigDecimal orderPrice, BigDecimal orderPriceRounded, VetoService.Outcome veto, String action,
            String reasonCode, ObjectNode orderJson, Double confidence, Instant now) {
        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), "SIGNAL",
                signal.signalId(), signal.source(), signal.agentVersion(), signal.symbol(),
                inputsSnapshotNode(signal, ctx, orderPrice, orderPriceRounded, veto),
                vetoResultsNode(veto.results()),
                action, reasonCode, orderJson, null, confidence,
                latencyNode(signal.createdAt(), now), null));
    }

    /**
     * The tranche-2 counterpart of {@link #logEntryDecision}. Add-tranche used to write only an
     * {@code executor_decision} row, so every audit query over {@code decision_log.order_json} had
     * a tranche-2 shaped hole — no sizing basis, no risk figures, no broker stop for the second
     * leg. It cannot reuse {@code logEntryDecision}: that needs a {@link VetoService.Outcome} this
     * path never builds (there is no signal to veto, only capital bounds to check).
     *
     * <p>{@code veto_results} is therefore synthesised as the BUDGET/HEAT_LIMIT pair the path
     * really did evaluate — the same two checks {@code CapitalBounds} answers for the entry path —
     * rather than left empty, which would read as "no checks ran".
     *
     * <p><b>{@code action = ADD_TRANCHE} is a deliberate deviation from spec §2.1</b>, which asked
     * for {@code ENTER}. A tranche 2 carries the SAME {@code signal_id} as the entry it adds to,
     * and every "the ENTER row of this signal" lookup takes the newest match
     * ({@code DecisionLogRepository.findBySignalIdAndAction}: {@code ORDER BY created_at DESC
     * LIMIT 1}). Written as {@code ENTER}, this row would shadow the entry row for
     * {@code OutcomeBatchJob.resolveEnterDecision} — and with it {@code outcome_log.log_id_ref},
     * the executor and hunter Brier scores, the stop-basis table — and for the depot history
     * "why", all of which would then read the nulls this row carries by design (a tranche 2 has no
     * signal of its own: no confidence, no reasoning, no agent version). {@code decision_log.action}
     * is free text, {@code ADD_TRANCHE} is already this codebase's word for the move
     * ({@code ExecutorDefaults}' tool schema), and §6.5's intent is served identically because it
     * queries {@code order_json}, not the action string.
     */
    private void logAddTrancheDecision(String runId, ExecutorPosition position, EntryContext ctx,
            BigDecimal orderPrice, BigDecimal orderPriceRounded, CapitalBounds.Result bounds,
            Sizing sizing, ObjectNode orderJson) {
        BigDecimal heatLimit = vetoConfig.totalBudget()
                .multiply(BigDecimal.valueOf(vetoConfig.heatPct()));
        List<VetoResult> synthesised = List.of(
                new VetoResult("BUDGET", bounds.budgetOk(),
                        "tranche " + bounds.trancheAccountCcy().toPlainString()
                                + (bounds.budgetOk() ? " within " : " beyond ")
                                + "cash and budget headroom"),
                new VetoResult("HEAT_LIMIT", bounds.heatOk(),
                        "new risk " + sizing.newRiskAccountCcy().toPlainString()
                                + (bounds.heatOk() ? " within " : " beyond ")
                                + "heat limit " + heatLimit.toPlainString()));

        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), "SIGNAL",
                position.sourceSignalId(), position.sourceAgent(), null, position.symbol(),
                inputsSnapshotNode(null, ctx, orderPrice, orderPriceRounded, null),
                vetoResultsNode(synthesised),
                "ADD_TRANCHE", null, orderJson, null, null, null, null));
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
        // The LLM's own decision confidence (0..1, optional) — logged for calibration, never
        // used to gate anything server-side.
        Double confidence = input.path("confidence").isNumber()
                ? input.path("confidence").asDouble() : null;

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
        BigDecimal orderPriceRounded;
        Sizing sizing;
        StopWindow window = null;
        BigDecimal proposedStop = stopPrice;
        boolean stopClamped = false;
        // The bounds StopWindowRounding actually used to arrive at the (possibly rounded) stop
        // below — OrderGuard.check must be given THESE, not sizing.stopMin()/stopMax(): sizing
        // always recomputes its own RAW window internally with no way to inject rounded bounds.
        BigDecimal roundedStopMin = null;
        BigDecimal roundedStopMax = null;
        // Audit trail for a take-profit that tick-rounding collapses onto/through the entry (see
        // below): the raw LLM value is preserved here even after `takeProfit` is nulled out, so
        // decision_log can distinguish "the LLM sent none" from "one was sent and dropped".
        BigDecimal proposedTakeProfit = takeProfit;
        boolean takeProfitDropped = false;
        // The price the protective leg will actually rest at, computed once, immediately after
        // sizing. Null only on the DATA_UNAVAILABLE placeholder path below, which the veto
        // short-circuits before anything reads it.
        BrokerStop.Result brokerStopResult = null;
        if (ctx.missing() == null || ctx.missing().isEmpty()) {
            // Risk layer is authoritative over the stop. Compute the sizer's stop window (pure fn
            // of side/price/ATR/swing-low, independent of the proposed stop) from the ROUNDED
            // order price — the same price size() below is called with (StopWindowRounding rule
            // 2) — round the proposed stop and the window bounds to the tick grid, clamp, then
            // size from the result. NO_STOP can no longer fire on LLM input; it remains only as
            // OrderGuard's defensive guard against a broken (null) server window.
            orderPrice = limitPrice != null ? limitPrice : ctx.price();
            orderPriceRounded = TickSize.roundEntry(side, orderPrice);

            window = sizer.stopWindow(side, orderPriceRounded, ctx.atrEff(), ctx.swingLow());

            // orderPriceRounded, not orderPrice: StopWindowRounding rule 2 requires the window to
            // come from the SAME price size() below is called with. This call site is the only
            // remaining place that rule can still be broken (StopWindowRounding itself cannot mix
            // prices — it only ever takes one). See
            // placeEntry_stopWindowRule2Regression_buy/_sell in the controller test suite, which
            // fails under a mutation back to `orderPrice` here even though the whole rest of the
            // suite stays green.
            StopWindowRounding.Result stopResult = StopWindowRounding.compute(
                    side, orderPriceRounded, ctx.atrEff(), ctx.swingLow(), stopPrice, sizer);
            stopPrice = stopResult.stop();          // authoritative stop used by guard, booking, take-profit
            roundedStopMin = stopResult.stopMin();
            roundedStopMax = stopResult.stopMax();
            stopClamped = stopResult.clamped();

            sizing = sizer.size(side, orderPriceRounded, ctx.atrEff(), ctx.swingLow(), stopPrice,
                    ctx.trancheAmount(), ctx.fxToAccount(), riskBudgetAccountCcy(), atrLabel(ctx));
            // Immediately after sizing, from side / logical stop / atrEff / entry price. The sizer
            // never sees this value and nothing feeds it back: the book's risk, the heat veto and
            // the hard trigger all reason about the LOGICAL stop.
            // A null stop can only come from a broken (null-bounds) server window, which
            // OrderGuard rejects as NO_STOP below — long before the bracket is built. Leaving the
            // result null here keeps BrokerStop's "logicalStop is never null" contract intact
            // instead of teaching it to swallow a value that must not exist.
            if (stopPrice != null) {
                brokerStopResult = BrokerStop.forEntry(side, stopPrice, ctx.atrEff(),
                        brokerStopBufferAtr, orderPriceRounded, maxBrokerStopPct);
            }
            if (takeProfit != null) {
                takeProfit = TickSize.roundTarget(side, takeProfit);
                // Rounding moves the target toward the entry (roundTarget: BUY floors, SELL
                // ceilings); on a target already close to the entry this can collapse it onto or
                // through orderPriceRounded (e.g. BUY entry 96.41, target 96.418 -> 96.41). A
                // target at or past the entry is not a valid bracket leg — omit it rather than
                // send a degenerate/invalid target (mirrors the "No synthetic take-profit"
                // philosophy documented below at the guard/booking step: better absent than
                // broken).
                boolean collapsed = "BUY".equals(side)
                        ? takeProfit.compareTo(orderPriceRounded) <= 0
                        : takeProfit.compareTo(orderPriceRounded) >= 0;
                if (collapsed) {
                    takeProfit = null;
                    takeProfitDropped = true;
                }
            }
        } else {
            orderPrice = null;
            orderPriceRounded = null;
            sizing = new Sizing(BigDecimal.ZERO, null, BigDecimal.ZERO, null, null, false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }

        VetoService.Outcome veto = vetoService.evaluate(signal, ctx, sizing, vetoConfig, orderPrice,
                patternRepo.findEnforced());
        List<String> vetoTrace = new ArrayList<>();
        for (VetoResult r : veto.results()) {
            vetoTrace.add(r.check() + ":" + (r.passed() ? "PASS" : "FAIL") + " (" + r.measured() + ")");
        }

        if (!veto.passed()) {
            RejectReason firstFailure = veto.firstFailure();
            String reason = firstFailure.name();
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, vetoTrace, "rejected by veto: " + reason, null, runId, null));
            // Transiente Raten-/Kapazitätsdeckel disqualifizieren das Signal nicht -> PENDING
            // lassen, damit der nächste Executor-Lauf es erneut prüft (Obergrenze: SIGNAL_EXPIRED).
            signalRepo.markStatus(signalId, firstFailure.isTransient() ? "PENDING" : "REJECTED");
            logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "REJECT", reason, null, confidence,
                    clock.instant());

            // A detected contradiction co-rejects the pending peer — but only when the entering
            // signal is itself terminally out (!isTransient). SIGNAL_EXPIRED (catalog #3) and
            // CONTRADICTION (#10) are both non-transient, so both intended cases fire. If the
            // entering signal is merely deferred by a transient cap (MAX_POSITIONS/BUDGET/
            // HEAT_LIMIT/COOLDOWN, #4–#7 ahead of CONTRADICTION), killing the peer here would let
            // the deferred signal enter on a later run once the cap clears — order-dependent, and
            // the opposite of "two contradicting theses → trade neither". Leaving both PENDING
            // makes them both terminal (CONTRADICTION) once the cap clears. Peer row is labeled
            // CONTRADICTION (its actual cause), not the entering signal's reason.
            if (veto.contradictingSignalId() != null && !firstFailure.isTransient()) {
                String otherId = veto.contradictingSignalId();
                signalRepo.markStatus(otherId, "REJECTED");
                decisionRepo.insert(new ExecutorDecision(null, otherId, signal.symbol(), false,
                        RejectReason.CONTRADICTION.name(), vetoTrace,
                        "contradiction pair with " + signalId, null, runId, null));
            }

            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason, "veto_trace", vetoTrace)));
        }

        if (sizing.rejectCause() != null || sizing.qty() == null || sizing.qty().signum() == 0) {
            // Three different zero paths, three different reasons. NO_R preserves today's outcome:
            // OrderGuard used to reject the collapsed stop as NO_STOP, and the rPerShare-first
            // ordering now returns before the guard is ever reached, so the controller emits the
            // same reason itself. RISK_TOO_WIDE is new and terminal.
            RejectReason cause = switch (sizing.rejectCause() == null
                    ? Sizing.RejectCause.NOTIONAL_ZERO : sizing.rejectCause()) {
                case NOTIONAL_ZERO -> RejectReason.TRANCHE_TOO_SMALL;
                case NO_R -> RejectReason.NO_STOP;
                case RISK_ZERO -> RejectReason.RISK_TOO_WIDE;
            };
            String reason = cause.name();
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, vetoTrace, "rejected: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "REJECT", reason, null, confidence,
                    clock.instant());
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
        OrderGuard.Result guard = orderGuard.check(side, qty, orderPriceRounded, stopPrice,
                roundedStopMin, roundedStopMax, connection, connection);

        if (!guard.ok()) {
            String reason = guard.reason().name();
            List<String> trace = new ArrayList<>(vetoTrace);
            trace.add("ORDER_GUARD:" + reason);
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    reason, trace, "rejected by order guard: " + reason, null, runId, null));
            signalRepo.markStatus(signalId, "REJECTED");
            logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "REJECT", reason, null, confidence,
                    clock.instant());
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", reason)));
        }

        // No synthetic take-profit. Until 2026-07-26 a missing take_profit was filled with a wide
        // 3R target, because Agora's place_bracket used to require one. It no longer does
        // (PlaceBracketTool: "takeProfitLimit is optional (since 2026-07-25): null means entry +
        // stop"), and the synthesis was actively harmful: the three entries ever placed carried
        // targets at +23.8 / +23.9 / +24.3 %, and Saxo rejected the tranche's equivalent target at
        // +28 % with TooFarFromEntryOrder — taking the whole bracket down, PROTECTIVE STOP
        // INCLUDED. The entry path was one wide initial stop away from the same failure.
        //
        // This strategy exits via the trailing chandelier / giveback stops, never via a fixed
        // target, so nothing is lost. An explicit take_profit from the LLM is still honoured and
        // passed through unchanged — only the invention is gone. Do NOT reintroduce a default.

        // Idempotency guard: only relevant on a retry after a prior broker error. If the previous
        // attempt actually reached the broker (committed but reported unavailable), an order
        // already exists for this clientRef (=signalId). Adopt it instead of placing a second
        // order. Agora does not dedupe on clientRef, so this check is the only double-order
        // protection on the PENDING-retry path. The whole guard + placement is wrapped in one try
        // so an orderByRef outage (broker down) degrades to the same retriable BROKER_ERROR path
        // as a placement failure, rather than crashing the handler.
        PlacedBracket placed;
        try {
            int priorBrokerErrors = decisionRepo.countByReason(signalId, "BROKER_ERROR");
            Optional<BrokerOrder> existing = priorBrokerErrors > 0
                    ? gateway.orderByRef(connection, signalId)
                    : Optional.empty();
            boolean adoptable = existing.isPresent() && isLiveOrder(existing.get().status());
            if (adoptable) {
                BrokerOrder eo = existing.get();
                // Book the live order's actual qty, not the freshly re-computed sizer qty — a
                // later-run retry can produce a different qty from the sizer, which would diverge
                // the DB position from the real broker order. Price cannot be reliably
                // reconstructed from a working order, so the price recompute is left as-is.
                if (eo.qty() != null) {
                    qty = eo.qty();
                }
                // Saxo/live brackets expose no leg ids — null is expected and matches a fresh
                // placement.
                placed = new PlacedBracket(eo.orderId(), null, null, eo.clientRef(), eo.status());
                decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                        "DUPLICATE", vetoTrace,
                        "idempotent retry: existing broker order " + eo.orderId()
                                + " for clientRef " + signalId + " adopted, not re-placed",
                        eo.orderId(), runId, null));
            } else if (decisionRepo.countByReasonInRun(signalId, "BROKER_ERROR", runId)
                    >= maxBrokerCallsPerRun) {
                // In-run throttle. Without it a retry storm inside one night (429 → duplicate →
                // 429) hammers the broker AND — before 2026-07-26, when the cap still counted
                // rows — burned the signal's entire lifetime attempt budget in a single run.
                //
                // Deliberately NOT reason "BROKER_ERROR": this row must not feed the attempt cap,
                // or the throttle would inflate the very counter it exists to protect.
                // Non-terminal — the next run starts with a fresh per-run budget.
                //
                // Order matters: adoption is checked FIRST. An already-existing broker order must
                // be adopted even when the call budget is spent, or it stays open without a DB
                // counterpart.
                decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                        "BROKER_RETRY_EXHAUSTED", vetoTrace,
                        "broker retry budget for this run exhausted: " + maxBrokerCallsPerRun
                                + "/" + maxBrokerCallsPerRun,
                        null, runId, null));
                logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "REJECT",
                        "BROKER_RETRY_EXHAUSTED", null, confidence, clock.instant());
                return ResponseEntity.ok(Map.of("output",
                        Map.of("placed", false, "reason", "BROKER_RETRY_EXHAUSTED")));
            } else {
                BracketRequest req = new BracketRequest(signal.symbol(), side, qty, orderPriceRounded,
                        brokerStopResult.price(), takeProfit, signalId, null);
                placed = gateway.placeBracket(connection, req);
            }
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), false,
                    "BROKER_ERROR", vetoTrace, "broker call failed: " + e.getMessage(),
                    null, runId, null));
            // Count failed RUNS in a rolling window, not rows over all time. Rows counted every
            // retry of one night as a separate attempt; the window lets an already-fixed defect
            // heal instead of scarring the signal forever. The row inserted just above is part of
            // the count, so the current run is already included: failedRuns == maxBrokerAttempts
            // means this run was the maxBrokerAttempts-th failed one.
            int failedRuns = decisionRepo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR",
                    clock.instant().minus(Duration.ofHours(brokerAttemptWindowHours)));
            if (failedRuns >= maxBrokerAttempts) {
                signalRepo.markStatus(signalId, "REJECTED");
            }
            // else: leave PENDING so a corrected retry (this run or a later run) can succeed
            logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "REJECT", "BROKER_ERROR", null,
                    confidence, clock.instant());
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "BROKER_ERROR", "error", e.getMessage())));
        }

        String brokerOrderId = placed.bracketId();
        String stopOrderId = placed.stopLegId();

        try {
            long positionId = positionRepo.insert(new ExecutorPosition(null, connection,
                    signal.symbol(), side, qty, orderPriceRounded, stopPrice, stopPrice, 1,
                    null, signal.killCriteria(), signalId, signal.source(), null, null,
                    "OPEN", brokerOrderId,
                    orderPriceRounded, null, 0, null, null, null, null, stopOrderId,
                    ctx.candidateSector(), ctx.dayHigh(), null, null, 0, null, null,
                    orderPriceRounded, null, null, null, false,
                    brokerStopResult.price(), null));

            positionRepo.setEntryExpiresAt(positionId, entryExpiry(clock.instant(), entryGtdDays));

            signalRepo.markStatus(signalId, "ACCEPTED");

            try {
                decisionRepo.insert(new ExecutorDecision(null, signalId, signal.symbol(), true,
                        null, vetoTrace, "entry placed", brokerOrderId, runId, null));

                ObjectNode orderJson = mapper.createObjectNode();
                orderJson.put("type", "limit_bracket");
                orderJson.put("qty", qty);
                orderJson.put("limit_price", orderPriceRounded);
                orderJson.put("stop_price", stopPrice);
                orderJson.put("take_profit", takeProfit);
                orderJson.put("stop_basis", sizing.stopBasis());
                orderJson.put("r_per_share", sizing.rPerShare());
                orderJson.put("position_risk", sizing.newRiskAccountCcy());
                orderJson.put("gtd_days", entryGtdDays);
                orderJson.put("stop_clamped", stopClamped);
                orderJson.put("proposed_stop", proposedStop);
                // Raw window (the risk layer's un-tick-rounded stop anchor/floor), computed from
                // orderPriceRounded — NOT a pre-branch/pre-tick-rounding value (there was no
                // tick-rounding before this branch, so nothing here is "continuity" with it).
                //
                // Naming deviation from spec (deliberate, kept as-is): the spec's
                // Audit-Konsistenz section wants the ENFORCED bounds in stop_min/stop_max and the
                // raw ones in stop_min_raw/stop_max_raw. This code ships the opposite — raw in
                // stop_min/stop_max, enforced in stop_min_rounded/stop_max_rounded below — to
                // preserve the existing stop_min/stop_max key for dashboards/queries already
                // reading it, avoiding a breaking rename. Do not "fix" this into a rename.
                orderJson.put("stop_min", window != null ? window.stopMin() : null);
                orderJson.put("stop_max", window != null ? window.stopMax() : null);
                // The bounds OrderGuard actually enforced (tick-rounded; equal to the raw window
                // above only in the degenerate branch, where rounding is skipped entirely).
                // Without this pair the audit trail cannot show why a stop next to a bound was
                // accepted or rejected.
                // The SIZER's own verdict on the stop it was handed, from the window IT derived
                // internally. Distinct from stop_min/stop_max above, which come from the separate
                // sizer.stopWindow() call: the two agree only while both are given the same ATR,
                // so writing this makes the sizer's ATR argument observable instead of inert (it
                // otherwise reaches nothing a caller reads). placeEntryUsesTheSameAtrForWindow
                // ClampAndSizing pins window, clamp and sizing together through this key.
                orderJson.put("stop_in_window", sizing.stopInWindow());
                orderJson.put("stop_min_rounded", roundedStopMin);
                orderJson.put("stop_max_rounded", roundedStopMax);
                orderJson.put("proposed_take_profit", proposedTakeProfit);
                orderJson.put("take_profit_dropped", takeProfitDropped);
                orderJson.put("broker_stop", brokerStopResult.price());
                orderJson.put("broker_stop_buffer_atr", brokerStopBufferAtr);
                orderJson.put("broker_stop_clamped", brokerStopResult.clamped());
                orderJson.put("broker_stop_capped", brokerStopResult.capped());
                orderJson.put("qty_notional", sizing.qtyNotional());
                orderJson.put("qty_risk", sizing.qtyRisk());
                orderJson.put("sizing_basis", sizing.sizingBasis());
                orderJson.put("reject_cause",
                        sizing.rejectCause() == null ? null : sizing.rejectCause().name());
                orderJson.put("risk_pct", riskPct);
                orderJson.put("atr_short", ctx.atrShort());
                orderJson.put("atr_effective", ctx.atrEff());
                orderJson.put("position_risk_broker", positionRiskBroker(side, qty,
                        orderPriceRounded, brokerStopResult.price(), ctx.fxToAccount()));
                logEntryDecision(runId, signal, ctx, orderPrice, orderPriceRounded, veto, "ENTER", null, orderJson,
                        confidence, clock.instant());
            } catch (RuntimeException e) {
                // Position and signal status are durably persisted — the order is managed.
                // Only the accepted-audit row(s) are missing; log it, but do not flip the response
                // into a false ORPHANED_ORDER (that would contradict persisted state).
                log.error("accepted-audit decisionRepo.insert failed for signal {} position {} "
                                + "broker order {}: {}",
                        signalId, positionId, brokerOrderId, e.getMessage(), e);
            }

            executorNotifier.notifyEntryPlaced(signal, side, qty, orderPriceRounded, stopPrice, connection);

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
        int unknownActions = 0;
        JsonNode rawDecisions = inputOf(body).path("decisions");
        JsonNode decisions = coerceDecisions(rawDecisions);
        if (decisions == null) {
            // Present but unusable. Returning a bare recorded:0 is what let the stringified case
            // run undetected for weeks — say so, to the agent AND to the log.
            log.warn("submit-decision: run {} sent a 'decisions' argument that could not be read "
                            + "as a decision list (node type {}); NOTHING was persisted: {}",
                    runId, rawDecisions.getNodeType(), abbreviate(rawDecisions.toString()));
            return ResponseEntity.ok(Map.of("output", Map.of(
                    "recorded", 0,
                    "unknown_actions", 0,
                    "error", "the 'decisions' argument could not be read as a list of decision "
                            + "objects — resend it as a JSON array of objects with signal_id, "
                            + "symbol, action and rationale")));
        }
        if (!decisions.isEmpty()) {
            for (JsonNode d : decisions) {
                String action = d.path("action").asString("");
                String signalId = d.path("signal_id").asString("");
                // NOTE (slice-2): symbol is trusted from the request body and not cross-checked
                // against the stored signal's actual symbol — deferred per final review item #5.
                String symbol = d.path("symbol").asString("");
                String rationale = d.path("rationale").asString(null);

                // ENTER is written by /tools/place-entry (with the veto trace, the broker order id
                // and the real ACCEPTED/REJECTED status transition), and ADD_TRANCHE is written by
                // /tools/add-tranche (accepted=true, rationale "tranche 2 added: <reason>", plus
                // the ORPHANED_ORDER / BROKER_ERROR rows on its failure paths). Re-recording either
                // here would double-count that path in every audit query — and for ADD_TRANCHE the
                // duplicate is worse than a double count: this row is written accepted=false, so a
                // successfully placed tranche would be shadowed by a phantom refusal one row later.
                // Skipped silently and by design, not for want of handling; the prompt is written
                // to match (see prompts/executor.md).
                if ("ENTER".equals(action) || "ADD_TRANCHE".equals(action)) continue;

                // Signal-status side effects, per action:
                //   SKIP        — the agent declined a PENDING signal; SKIPPED is its terminal
                //                 verdict (unchanged behavior).
                //   HOLD        — an observation about an OPEN position whose source signal is
                //                 long ACCEPTED. Touching the status would overwrite the entry
                //                 verdict and processed_at with a maintenance-time note.
                // HOLD has no sensible transition in the existing vocabulary (PENDING / ACCEPTED /
                // REJECTED / SKIPPED / EXPIRED), and inventing one would break
                // RejectReason.isTransient()'s "PENDING means retry me" contract.
                // So: persist the row, leave the status alone.
                if (!"SKIP".equals(action) && !"HOLD".equals(action)) {
                    // Never swallow a decision we cannot classify: it is a prompt/schema drift
                    // signal, and a dropped row is an invisible hole in the audit trail.
                    log.warn("submit-decision: unknown action '{}' for signal {} ({}) in run {} — "
                                    + "decision NOT persisted", action, signalId, symbol, runId);
                    unknownActions++;
                    continue;
                }

                decisionRepo.insert(new ExecutorDecision(null, signalId, symbol, false,
                        null, List.of(), rationale, null, runId, null, action));
                if ("SKIP".equals(action)) {
                    signalRepo.markStatus(signalId, "SKIPPED");
                }
                recorded++;
            }
        }
        return ResponseEntity.ok(Map.of("output",
                Map.of("recorded", recorded, "unknown_actions", unknownActions)));
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
            if (p.entryFilled()) {
                recordPositionContext(p);
                mirrorActiveStop(p);
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("symbol", p.symbol());
            node.put("signal_id", p.sourceSignalId());
            node.put("side", p.side());
            node.put("qty", p.qty());
            node.put("entry_filled", p.entryFilled());
            node.put("entry_price", p.entryPrice());
            node.put("active_stop", p.activeStop());
            // The catastrophe backstop, alongside the level that decides. position_context's
            // active_stop mirror (mirrorActiveStop) keeps mirroring the LOGICAL stop deliberately:
            // proximity alerts and the daywalker/renfield prompts watch the level that decides,
            // not the backstop.
            node.put("broker_stop", p.brokerStop());
            node.put("current_price", p.currentPrice());
            node.put("atr", p.atr());
            node.put("atr_short", p.atrShort());
            node.put("chandelier_level", p.chandelierLevel());
            node.put("r_current", p.rCurrent());
            node.put("mfe_r", p.mfeR());
            node.put("days_held", p.daysHeld());
            node.put("kill_criteria", p.killCriteria());
            node.put("trim_count", p.trimCount());
            node.put("suggested_fraction", p.suggestedFraction());

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

    /**
     * Records (or idempotently confirms) the {@code position_context} row for a confirmed-filled
     * position, so the depot position is later joinable to its research thesis / kill-criteria /
     * stops (the depot-as-single-source-of-truth goal). Called once per {@code fetch-open-positions}
     * pass for every {@code entryFilled} position; {@link PositionContextRepository#upsertOnOpen}
     * is idempotent (ON CONFLICT DO NOTHING against the open partial unique index), so repeating
     * this on every pass for an already-recorded symbol is a cheap no-op, not a duplicate write.
     *
     * <p>{@code ExecutorSignal}/{@code ExecutorPosition} carry no {@code verdictId} — the
     * executor's own signal pipeline (Prey -> {@code PreySignalMapper} -> {@code ExecutorSignal})
     * has no link back to a {@code Verdict} row, unlike gropar's {@code WatchlistItem.verdictId()}.
     * So {@code verdictId} is always {@code null} here (never fabricated); {@code killCriteria},
     * {@code horizon}, and {@code thesisSnapshot} are the executor's own real, already-persisted
     * signal/position data, not derived from a verdict.
     *
     * <p>Fail-soft: any failure here is logged at WARN and swallowed — a context-write failure
     * must never fail {@code fetch-open-positions} or the maintenance pipeline it reports on.
     *
     * <p>{@code initial_stop} must be the position's placement-time, immutable stop — never
     * {@link EnrichedPosition#activeStop()}, which can already reflect this SAME maintenance
     * pass's ratchet (a position that transitions unfilled -> filled is ratchet-eligible in the
     * very pass that builds {@code p}; {@code ReconcileService} -> hard-trigger -> ratchet all
     * run over {@code filledSurvivors} before {@code EnrichedPosition} is built). Because
     * {@link PositionContextRepository#upsertOnOpen} is {@code ON CONFLICT DO NOTHING}, a wrong
     * value here would freeze permanently. {@link ExecutorPosition#initialStop()} is the true
     * immutable field (set once on insert, never updated by
     * {@link ExecutorPositionRepository#updateMaintenance}), so it is re-fetched by id here
     * rather than trusting the enriched view.
     */
    private void recordPositionContext(EnrichedPosition p) {
        try {
            JsonNode killCriteria = (p.killCriteria() == null || p.killCriteria().isEmpty())
                    ? null : mapper.valueToTree(p.killCriteria());
            String horizon = resolveHorizon(p.sourceSignalId());
            JsonNode thesis = resolveThesis(p.sourceSignalId());
            ExecutorPosition position = positionRepo.findById(p.id());
            BigDecimal initialStop = position != null ? position.initialStop() : p.activeStop();
            positionContextRepo.upsertOnOpen(connection, p.symbol(), null, killCriteria, horizon,
                    thesis, initialStop, "executor");
            // Heal a row a reconciler "none" shadow (or an earlier thesis-less write) left behind —
            // the ON CONFLICT DO NOTHING upsert above can't update an existing row. COALESCE-if-null.
            positionContextRepo.updateContextIfNull(connection, p.symbol(), thesis, killCriteria,
                    horizon, initialStop);
        } catch (RuntimeException e) {
            log.warn("position_context write failed for filled position {} ({}): {}",
                    p.id(), p.symbol(), e.getMessage(), e);
        }
    }

    /** Mirror the live ratcheted stop into position_context for stopguard. Long-only: the
     *  stopguard/RiskMetrics stack is side-blind and treats price ≤ stop as BREACHED, so a SELL's
     *  above-price stop would fire false alerts (M3). Skip a null stop so we never blank a prior
     *  non-null stop (m5). Fail-soft. */
    private void mirrorActiveStop(EnrichedPosition p) {
        if (!"BUY".equals(p.side())) return;
        if (p.activeStop() == null) return;
        try {
            positionContextRepo.updateActiveStopBySymbol(connection, p.symbol(), p.activeStop());
        } catch (RuntimeException e) {
            log.warn("active_stop mirror failed for {} ({}): {}", p.id(), p.symbol(), e.getMessage());
        }
    }

    /** Same null-safe signal lookup idiom as {@link #resolvePositionMechanism}. */
    private String resolveHorizon(String sourceSignalId) {
        if (sourceSignalId == null) return null;
        ExecutorSignal source = signalRepo.findById(sourceSignalId);
        return source == null ? null : source.horizon();
    }

    /** Same null-safe signal lookup idiom as {@link #resolveHorizon}. */
    private JsonNode resolveThesis(String sourceSignalId) {
        if (sourceSignalId == null) return null;
        ExecutorSignal source = signalRepo.findById(sourceSignalId);
        return source == null ? null : source.thesis();
    }

    // -------------------------------------------------------------------
    // exit-position — LLM SOFT full exit; exits on FILLED positions are always permitted (no
    // veto). Unfilled GTD entries are rejected NOT_FILLED — there is nothing to flatten.
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

        // A position whose GTD entry has no confirmed fill holds nothing at the broker — an
        // LLM exit would flatten zero holdings and book a fabricated close (+ cooldown).
        // `entry_expires_at` doubles as the persisted unfilled marker: set at placement,
        // cleared by ReconcileService on a confirmed fill and by EntryExpiryService on cancel.
        if (position.entryExpiresAt() != null) {
            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, null, null,
                    "REJECT", "NOT_FILLED", null,
                    "exit_position on unfilled entry (position " + position.id()
                            + ") — no broker holdings; awaiting fill or GTD expiry",
                    confidence, null, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "reason", "NOT_FILLED")));
        }

        // The exit_position schema enum pins fraction to exactly 0.33 / 0.5 / 1.0 (or absent,
        // defaulting to a full 1.0 exit); read as a primitive double and compare with exact
        // equality against those literals rather than a tolerance-based compare.
        double fraction = input.path("fraction").isNumber() ? input.path("fraction").asDouble() : 1.0;
        if (fraction != 0.33 && fraction != 0.5 && fraction != 1.0) {
            return ResponseEntity.ok(Map.of("output", Map.of(
                    "exited", false, "reason", "SCHEMA_INVALID",
                    "reasoning", "fraction must be one of 0.33, 0.5, 1.0, got " + fraction)));
        }

        // Code-enforced escalation ladder: the LLM may exit MORE aggressively than the floor
        // implied by the position's persisted trim_count, but never less.
        double floor = ladderFloor(position.trimCount());
        if (fraction < floor) {
            return ResponseEntity.ok(Map.of("output", Map.of(
                    "exited", false, "reason", "SCHEMA_INVALID",
                    "reasoning", "ladder floor is " + floor + " (trim_count=" + position.trimCount()
                            + "); fraction " + fraction + " would undercut it")));
        }

        // Compute the complement in BigDecimal, not primitive double: 1 - 0.33 in double is
        // 0.6699999999999999, which would floor qty=100 to remaining 66 instead of the intended
        // 67 -- a real bookkeeping drift over repeated trims. BigDecimal.valueOf(0.33) is the
        // exact decimal "0.33", so ONE.subtract(...) yields an exact "0.67".
        BigDecimal remaining = position.qty()
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(fraction)))
                .setScale(0, RoundingMode.FLOOR);
        boolean fullExit = fraction == 1.0 || remaining.signum() <= 0;

        // The broker fraction must follow the BOOK's exit semantics, not the raw request:
        // whenever the book treats this as a full exit (explicit fraction 1.0 OR a small-qty
        // trim whose remainder floors to 0 shares), the broker must be flattened fully too --
        // otherwise the book would close (+ cooldown) while the broker keeps an unmanaged
        // remainder. BigDecimal.ONE (scale 0, not BigDecimal.valueOf(1.0) with scale 1) is the
        // canonical full-flatten value callers (gateway adapters, tests) expect.
        BigDecimal gatewayFraction = fullExit ? BigDecimal.ONE : BigDecimal.valueOf(fraction);

        CloseResult cr;
        try {
            cr = gateway.flatten(connection, symbol, gatewayFraction);
        } catch (BrokerRejectedException e) {
            // A rejection can still change broker state: Agora rolls protection back and may
            // issue NEW leg ids before reporting the rejection. The trim itself did not happen,
            // so qty, trim_count AND soft_confirm_count must stay untouched — reusing recordTrim
            // here (fix round 1 finding) would wrongly reset both the soft-confirm ladder and the
            // stop_legs_collapsed flag, since recordTrim always zeroes/recomputes them for an
            // actual trim. repointStopLegs touches ONLY the stop-leg id columns.
            //
            // Any currently-recorded stop-leg id that is not named (as a `replaces` target) in
            // e.protectiveLegs() is nulled rather than left stale: Agora's own rollback
            // (SaxoBrokerProvider.interleaveRollback) can break at the first failure and report
            // fewer live legs than were cancelled — precisely the LEG_RESTORE_FAILED_UNPROTECTED
            // case. Keeping an unmatched id would point Dracul at an order that was cancelled and
            // never replaced; the next ratchet run would fail with LEG_NOT_FOUND forever.
            // LEG_CANCEL_INCOMPLETE does not have this gap (Agora self-maps every uncancelled leg
            // back to its own id), so this is safe there too.
            //
            // BUT repointStopLegs must NOT run unconditionally: several Saxo reject codes fire
            // BEFORE the leg-cancel loop ever runs (INVALID_FRACTION, SYMBOL,
            // QTY_EXCEEDS_POSITION, QTY_ROUNDED_TO_ZERO, CLOSE_ALREADY_PENDING — see
            // SaxoBrokerProvider.flatten) and every Alpaca flatten rejection never touches a leg
            // at all. On those, e.protectiveLegs() is legitimately empty, and repointStopLegs
            // treats "not named" as "dead" — it would null BOTH live stop columns for a broker
            // rejection that never changed broker state, permanently orphaning working stop
            // orders (nothing restores them: updateMaintenance's stop_order_id is a COALESCE that
            // never overwrites with NULL). Repoint only when Agora actually touched a leg
            // (non-empty protectiveLegs) or the reject code is one of the three leg-restore codes
            // — the third, LEG_RESTORE_FAILED_UNPROTECTED, can legitimately carry an EMPTY list in
            // the worst case (interleaveRollback stops with nothing live), and that is exactly the
            // one case where nulling both columns is truthful.
            boolean legCancelWasAttempted = (e.protectiveLegs() != null && !e.protectiveLegs().isEmpty())
                    || "LEG_CANCEL_INCOMPLETE".equals(e.rejectCode())
                    || "LEG_RESTORE_FAILED".equals(e.rejectCode())
                    || "LEG_RESTORE_FAILED_UNPROTECTED".equals(e.rejectCode());
            if (legCancelWasAttempted) {
                List<RestoredLeg> restored = e.protectiveLegs() != null ? e.protectiveLegs() : List.of();
                positionRepo.repointStopLegs(position.id(), restored);
                // The leg rows carry the ids the stop ratchet actually addresses, so they need the
                // same repoint the columns just got. Without it the ratchet keeps patching orders
                // the rollback replaced and fails LEG_NOT_FOUND on every run.
                repointLegStops(position.id(), restored);
            }
            // Agora's NO_POSITION reject code is named separately from every other reject code:
            // it is the structural case where the position is simply gone at the broker, not a
            // business rejection whose detail (LEG_RESTORE_FAILED_UNPROTECTED etc.) is worth
            // surfacing verbatim. A null reject code (Agora omitted the field) falls back to a
            // defined name rather than a null reason_code -- an escalation row nothing can query
            // for is as good as lost.
            // One vocabulary with HardTriggerService.flattenOrEscalate, which files the SAME
            // rejections as BROKER_REJECTED. Writing Agora's wire code straight into reason_code
            // here meant a query for BROKER_REJECTED found the hard-trigger half and missed this
            // one, for the identical broker event. Two names, one condition.
            //
            // It also retires a string that had come to mean two opposite things: historical
            // production rows with reason_code = 'NOT_FOUND' meant "the position is gone", while a
            // generic 404 inside flatten says nothing about whether it exists. That case is now
            // reason_code = BROKER_REJECTED with reject_code = NOT_FOUND in inputs_snapshot, so
            // the old string is never written again and every surviving 'NOT_FOUND' row is
            // unambiguously historical.
            //
            // The wire code is not lost, it moves to a queryable field (the pattern
            // FILL_HISTORY_UNAVAILABLE's `withheld` established) and is named in the reasoning,
            // exactly as the hard-trigger path already does. It stays load-bearing in Java where
            // it always was -- the CRITICAL alert below still branches on
            // LEG_RESTORE_FAILED_UNPROTECTED, and repointStopLegs on the three leg-restore codes.
            boolean positionAlreadyGone = AGORA_NO_POSITION.equals(e.rejectCode());
            String flattenReasonCode = positionAlreadyGone ? "POSITION_ALREADY_GONE" : "BROKER_REJECTED";
            String flattenReasoning = positionAlreadyGone
                    ? "position already gone during soft-exit flatten: " + e.getMessage()
                    : "broker rejected soft-exit flatten ["
                            + (e.rejectCode() == null ? "no reject code" : e.rejectCode())
                            + "]: " + e.getMessage();
            ObjectNode rejectInputs = mapper.createObjectNode();
            rejectInputs.put("reject_code", e.rejectCode());
            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, rejectInputs, null,
                    "ESCALATE", flattenReasonCode, null, flattenReasoning,
                    confidence, null, null));
            // Alert only on the unprotected case — a plain LEG_CANCEL_INCOMPLETE / restored-but-
            // rejected trim keeps today's quiet escalation, or the alert loses meaning.
            if ("LEG_RESTORE_FAILED_UNPROTECTED".equals(e.rejectCode())) {
                telegram.notifyAlert(symbol, e.rejectCode(), "CRITICAL",
                        "partial close on " + symbol + " was rejected and left the remaining "
                                + "position unprotected: " + e.getMessage());
            }
            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "reason", "BROKER_ERROR")));
        } catch (BrokerUnavailableException e) {
            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, null, null,
                    "ESCALATE", "BROKER_UNAVAILABLE", null,
                    "broker unavailable during soft-exit flatten: " + e.getMessage(),
                    confidence, null, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "reason", "BROKER_ERROR")));
        }

        if (!fullExit) {
            // Quantities come from the BROKER, never from our own arithmetic: Dracul floors
            // qty × (1−fraction) while the broker floors qty × fraction and subtracts, which
            // differ by one share on four of five live positions. Falling back to the local
            // arithmetic only covers a provider that does not report closed/remaining qty — and
            // it is all-or-nothing: trusting one broker-reported field while falling back on the
            // other could yield qtyClosed + qtyRemaining != position.qty(), exactly the
            // OutcomeBatchJob weighted-R inflation this change removes. Not reachable through
            // today's gateway (it always reports both or neither), but kept safe against a future
            // provider that reports only one.
            boolean brokerReportedBoth = cr.closedQty() != null && cr.remainingQty() != null;
            BigDecimal qtyClosed = brokerReportedBoth ? cr.closedQty() : position.qty().subtract(remaining);
            BigDecimal qtyRemaining = brokerReportedBoth ? cr.remainingQty() : remaining;

            positionRepo.recordTrim(position.id(), qtyRemaining, position.trimCount() + 1,
                    cr.protectiveLegs(), cr.legsCollapsed());
            // The leg rows keep their pre-trim quantities here, deliberately. A partial close is
            // spread across the broker's tranches by the broker itself, and this response says
            // only how much was closed in total -- so any per-leg split written here would be our
            // arithmetic, not the broker's, which is exactly the defect the V45 backfill was
            // rewritten to avoid. The legs are stale until the next reconcile pass, where
            // ReconcileService.syncLegQuantities converges each one to its own WORKING stop's
            // reported qty (the broker's own number). Judged acceptable because nothing between
            // the two reads a leg quantity: StopRatchetService reads leg IDs and moves a price
            // level, never a quantity, and every quantity-based veto sizes off
            // executor_position.qty, which recordTrim just wrote from the broker's own
            // remainingQty. A leg that the trim drove to zero is likewise left to reconcile,
            // which CLOSES it -- executor_position_leg carries CHECK (qty > 0), so a zero leg is
            // unwritable by construction and must never be "updated" to nothing.

            ObjectNode orderJson = mapper.createObjectNode();
            orderJson.put("fraction", fraction);
            orderJson.put("qty_closed", qtyClosed);
            orderJson.put("qty_remaining", qtyRemaining);
            orderJson.put("price", cr.avgFillPrice());
            // Exact position linkage for the outcome batch job (decision_log has no position_id
            // column; order_json carries it) — without it, a same-day close+reentry on the same
            // symbol could leak another lifecycle's TRIM into the weighted-R math.
            orderJson.put("position_id", position.id());

            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, null, null,
                    "TRIM", null, orderJson, reasoning, confidence, null, null));

            return ResponseEntity.ok(Map.of("output", Map.of(
                    "exited", false, "trimmed", true, "fraction", fraction,
                    "qty_closed", qtyClosed, "qty_remaining", qtyRemaining)));
        }

        BigDecimal exitPrice = cr.avgFillPrice();

        if (exitPrice == null) {
            // The flatten was accepted but not yet confirmed filled -> stamp a pending-exit
            // marker and let ReconcileService finalize once the broker confirms. Closing here on
            // a guessed price would be the same class of bug as the verified PSMT incident:
            // booking a wrong exit price/R while the broker may still hold shares + a working
            // exit order.
            positionRepo.markPendingExit(position.id(), reason, cr.orderRef(), null, clock.instant());

            ObjectNode pendingInputs = mapper.createObjectNode();
            pendingInputs.put("active_stop", position.activeStop());

            ObjectNode pendingOrderJson = mapper.createObjectNode();
            pendingOrderJson.put("fraction", fraction);
            pendingOrderJson.put("position_id", position.id());

            decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "SOFT_TRIGGER", null, null, null, symbol, pendingInputs, null,
                    "EXIT_FULL", reason, pendingOrderJson, reasoning, confidence, null, null));

            return ResponseEntity.ok(Map.of("output",
                    Map.of("exited", false, "pending", true)));
        }

        RCalc rCalc = computeR(position, exitPrice);
        BigDecimal realizedR = rCalc.r();

        positionRepo.close(position.id(), exitPrice, realizedR, reason, "FILL", rCalc.denominator());
        // Book every leg out with the row -- the fifth and last lifecycle point that closes a
        // position (the other four are ReconcileService's closePositionFromLegs, its
        // RECONCILE_GONE leg loop, finalizePendingExitOrKeep, and EntryExpiryService's
        // cancelOpenLegs). This branch is a FULL exit that the broker filled immediately, so no
        // leg can survive it, and an OPEN leg under a CLOSED row would falsify the invariant V45
        // established and V46 checks.
        Instant legClosedAt = clock.instant();
        for (ExecutorPositionLeg leg : legRepo.findOpenByPosition(position.id())) {
            legRepo.closeLeg(leg.id(), exitPrice, reason, legClosedAt);
        }
        cooldownRepo.add(symbol, reason, clock.instant().plus(Duration.ofDays(cooldownDays)),
                "fresh setup only");

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("exit_price", exitPrice);
        inputs.put("realized_r", realizedR);
        inputs.put("active_stop", position.activeStop());

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", fraction);
        orderJson.put("position_id", position.id()); // exact linkage for the outcome batch job

        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "SOFT_TRIGGER", null, null, null, symbol, inputs, null,
                "EXIT_FULL", reason, orderJson, reasoning, confidence, null, null));

        executorNotifier.notifyExit(position, reason, exitPrice, realizedR, connection);

        return ResponseEntity.ok(Map.of("output",
                Map.of("exited", true, "exit_reason", reason)));
    }

    /** Code-enforced trim ladder floor: {@code trimCount} 0 -> 0.33, 1 -> 0.5, >=2 -> 1.0 (must
     *  fully flatten). The LLM may always exit more aggressively than this floor, never less. */
    static double ladderFloor(int trimCount) {
        if (trimCount <= 0) return 0.33;
        if (trimCount == 1) return 0.5;
        return 1.0;
    }

    /** Entry GTD expiry = {@code now} plus {@code gtdDays} calendar days, weekend-skipped: if the
     *  result lands on a Saturday or Sunday it rolls forward to the following Monday. This is a
     *  documented approximation — no exchange-holiday calendar in v1 (see
     *  {@code documentation/configuration.md}). */
    static Instant entryExpiry(Instant now, int gtdDays) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC).plusDays(gtdDays);
        while (zdt.getDayOfWeek() == DayOfWeek.SATURDAY || zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
            zdt = zdt.plusDays(1);
        }
        return zdt.toInstant();
    }

    /** Realized R together with the denominator (risk-per-share) it was actually divided by, so
     *  the same expression that produces {@code realized_r} also produces what gets persisted
     *  into {@code r_value} — see {@link ExecutorPositionRepository#close}. {@code r} is null
     *  exactly when the denominator was zero; {@code denominator} is then also null so nothing
     *  meaningless gets persisted in that case. */
    private record RCalc(BigDecimal r, BigDecimal denominator) {
    }

    private RCalc computeR(ExecutorPosition p, BigDecimal exitPrice) {
        BigDecimal numerator;
        BigDecimal denominator;
        if ("SELL".equals(p.side())) {
            numerator = p.entryPrice().subtract(exitPrice);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = exitPrice.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return new RCalc(null, null);
        BigDecimal r = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
        return new RCalc(r, denominator);
    }

    // -------------------------------------------------------------------
    // add-tranche — code-verified tranche-2 adds to an open tranche-1 position
    // -------------------------------------------------------------------

    /**
     * Applies a flatten rollback's restored protective legs to the leg rows, mirroring
     * {@link ExecutorPositionRepository#repointStopLegs} one table over.
     *
     * <p>Matching is by {@code replaces}: a restored leg names the id it took over from. An OPEN
     * leg whose recorded stop id no restored leg claims has its id nulled rather than left alone —
     * Agora's rollback can stop at the first failure and report fewer live legs than it cancelled,
     * so an unclaimed id is dead, not stale. A null id is a visible protection gap that
     * {@link StopRatchetService} reports; a stale one looks live and silently patches an order
     * that no longer exists.
     *
     * <p>Only OPEN legs are touched: a CLOSED or CANCELLED leg's stop id is history, and the
     * rollback has nothing to say about it.
     */
    private void repointLegStops(long positionId, List<RestoredLeg> restored) {
        for (ExecutorPositionLeg leg : legRepo.findOpenByPosition(positionId)) {
            if (leg.stopOrderId() == null) continue;
            String replacement = restored.stream()
                    .filter(r -> leg.stopOrderId().equals(r.replaces()))
                    .map(RestoredLeg::orderId)
                    .findFirst().orElse(null);
            if (!leg.stopOrderId().equals(replacement)) {
                legRepo.repointLegStop(leg.id(), replacement);
            }
        }
    }

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
        // Rounding boundary: detect() gets the RAW ctx.price() — it decides ELIGIBILITY with a
        // strict compareTo against entryDayHigh (Tranche2Detector.java:72-73), and ctx.price() is
        // a market close, not an order price. Rounding it here could turn a today-eligible add
        // (e.g. entryDayHigh 70.50, price 70.504) ineligible (70.50 > 70.50 is false). Same split
        // as the entry path: decision raw, mechanics rounded. Everything from here down is
        // mechanics and works on the rounded price/stop.
        Tranche2Detector.Tranche2Status t2 = tranche2Detector.detect(position, ctx.price(),
                ctx.pendingSignals(), positionMechanism);
        if (!t2.eligible()) {
            String reason = RejectReason.NOT_ELIGIBLE.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "tranche 2 not eligible", null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        // Tick-round the order price (away from the fill, same as the entry path) and the
        // position's existing active stop (toward the entry) onto the grid. StopWindowRounding is
        // NOT reused here: that class clamps a freshly PROPOSED stop into a window derived from
        // the rounded price, degeneracy-checked before the clamp -- none of which applies to a
        // tranche-2 add, which sizes against the position's EXISTING active stop (never re-derived
        // from current ATR/swing levels, see below) rather than a new proposal to be windowed.
        // A plain TickSize.roundEntry/roundStop pair is the whole sequence this path needs.
        BigDecimal pxRounded = TickSize.roundEntry(position.side(), ctx.price());
        BigDecimal stopRounded = TickSize.roundStop(position.side(), position.activeStop());

        // No OrderGuard runs on this path (none of :1180-:1360 calls orderGuard.check), so the
        // collapse case OrderGuard would normally catch must be checked explicitly here: rounding
        // the price and the stop independently can collapse a raw-valid pair onto the same tick
        // (e.g. price 70.504 / stop 70.498 -> both 70.50). Reject with NO_STOP rather than send a
        // zero-width (or inverted) bracket; a tranche has no signal status of its own to flip.
        boolean stopValid = "BUY".equals(position.side())
                ? stopRounded.compareTo(pxRounded) < 0
                : stopRounded.compareTo(pxRounded) > 0;
        if (!stopValid) {
            String reason = RejectReason.NO_STOP.name();
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    reason, List.of(), "rejected: " + reason, null, runId, null));
            return ResponseEntity.ok(Map.of("output", Map.of("placed", false, "reason", reason)));
        }

        // Tranche-2 sizing reuses the position's EXISTING active stop — it predates this add and
        // is never re-derived from the *current* ATR/swing levels, so PositionSizer.stopInWindow()
        // (which validates freshness against those current levels) is deliberately ignored here;
        // only qty/risk outputs are used. Sized from the ROUNDED price/stop, not the raw ones, so
        // HEAT_LIMIT/BUDGET below see the same (possibly smaller) rPerShare the broker will
        // actually work with.
        Sizing sizing = sizer.size(position.side(), pxRounded, ctx.atrEff(), ctx.swingLow(),
                stopRounded, ctx.trancheAmount(), ctx.fxToAccount(), riskBudgetAccountCcy(),
                atrLabel(ctx));

        if (sizing.rejectCause() != null || sizing.qty() == null
                || sizing.qty().compareTo(BigDecimal.ONE) < 0) {
            // The same three-way routing as place-entry: three zero paths, three reasons.
            RejectReason cause = switch (sizing.rejectCause() == null
                    ? Sizing.RejectCause.NOTIONAL_ZERO : sizing.rejectCause()) {
                case NOTIONAL_ZERO -> RejectReason.TRANCHE_TOO_SMALL;
                case NO_R -> RejectReason.NO_STOP;
                case RISK_ZERO -> RejectReason.RISK_TOO_WIDE;
            };
            String reason = cause.name();
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

        // Tranche 2 bekommt bewusst KEINEN eigenen Take-Profit. Der bis 2026-07-25 hier
        // synthetisierte 3R-Zielkurs lag bei +28 % vom Entry und wurde von Saxo mit
        // TooFarFromEntryOrder abgelehnt — der Per-Leg-Body benannte genau dieses Leg.
        // Die daraus folgende Kette (Fallback → 429 → Retry → 409) verdeckte den Grund
        // und verhinderte die Tranche seit dem 2026-07-20 in jedem Nachtlauf.
        // Der Ausstieg der Gesamtposition wird ohnehin vom Exit-Lifecycle gesteuert,
        // nicht von einem Zielkurs an der zweiten Tranche.
        // The new leg may rest at a different broker level than tranche 1's (an older atrEff). That
        // transient two-level state is accepted: the next ratchet sends ONE buffered price to every
        // open leg and they converge.
        BrokerStop.Result brokerStopResult = BrokerStop.forEntry(position.side(), stopRounded,
                ctx.atrEff(), brokerStopBufferAtr, pxRounded, maxBrokerStopPct);

        String signalId = position.sourceSignalId();
        String clientRef = "t2-" + (signalId != null ? signalId : "pos-" + position.id());

        // Idempotency guard + attempt cap — the exact mirror of place-entry's guard above, on the
        // tranche clientRef ("t2-…", distinct from the entry ref, so the two adoptions can never
        // collide). It became mandatory when Agora switched X-Request-ID to a fresh random value
        // per attempt: Saxo's request-id dedupe used to swallow a re-sent tranche order, and no
        // longer does. Without this, a retry after a "committed but reported unavailable" attempt
        // would open a SECOND tranche-2 order.
        //
        // Accepted imprecision: countByReason(signalId, "BROKER_ERROR") counts entry AND tranche
        // broker errors of the same signal on one axis. That is deliberate — both mean "this
        // broker is currently unhealthy for this signal", and a second counting axis would be
        // more state for little gain. Do NOT "fix" this later as a bug.
        //
        // Since 2026-07-26 the cap counts distinct RUNS inside a rolling window, not rows over all
        // time; the duplicate guard above keeps the unwindowed lifetime count on purpose.
        //
        // A position without a sourceSignalId (manual/imported) has no counting axis at all, so it
        // keeps the pre-guard behaviour of placing unconditionally.
        BigDecimal trancheQty = sizing.qty();
        PlacedBracket placed;
        try {
            // Lifetime count — the DUPLICATE-protection axis. Deliberately NOT windowed: a still
            // open order from four days ago must still be found, or a second one lands next to it.
            int priorBrokerErrors = signalId != null
                    ? decisionRepo.countByReason(signalId, "BROKER_ERROR")
                    : 0;
            Optional<BrokerOrder> existing = priorBrokerErrors > 0
                    ? gateway.orderByRef(connection, clientRef)
                    : Optional.empty();
            boolean adoptable = existing.isPresent() && isLiveOrder(existing.get().status());
            if (adoptable) {
                BrokerOrder eo = existing.get();
                // Book the live order's actual qty, not the freshly re-computed sizer qty — a
                // later-run retry can produce a different qty from the sizer, which would diverge
                // the DB position from the real broker order.
                if (eo.qty() != null) {
                    trancheQty = eo.qty();
                }
                // Saxo/live brackets expose no leg ids — null is expected and matches a fresh
                // placement.
                placed = new PlacedBracket(eo.orderId(), null, null, eo.clientRef(), eo.status());
                decisionRepo.insert(new ExecutorDecision(null, signalId, symbol, false,
                        "DUPLICATE", List.of(),
                        "idempotent retry: existing broker order " + eo.orderId()
                                + " for clientRef " + clientRef + " adopted, not re-placed",
                        eo.orderId(), runId, null));
            } else {
                // Both counting queries live inside this branch on purpose: an adoptable order is
                // taken regardless of any budget (see the ordering invariant on the entry path),
                // so the adoption case must not pay for two extra DB round-trips.
                int callsThisRun = signalId != null
                        ? decisionRepo.countByReasonInRun(signalId, "BROKER_ERROR", runId)
                        : 0;
                if (signalId != null && callsThisRun >= maxBrokerCallsPerRun) {
                    // In-run throttle — see the identical guard on the entry path. Not terminal,
                    // and deliberately not reason "BROKER_ERROR" so it cannot inflate the cap.
                    decisionRepo.insert(new ExecutorDecision(null, signalId, symbol, false,
                            "BROKER_RETRY_EXHAUSTED", List.of(),
                            "broker retry budget for this run exhausted: " + maxBrokerCallsPerRun
                                    + "/" + maxBrokerCallsPerRun,
                            null, runId, null));
                    return ResponseEntity.ok(Map.of("output",
                            Map.of("placed", false, "reason", "BROKER_RETRY_EXHAUSTED")));
                }
                // Failed RUNS inside the window — the attempt-cap axis, separate from the
                // lifetime count above. Rows counted every retry of one night as its own attempt,
                // which burned the whole budget in a single run.
                int failedRuns = signalId != null
                        ? decisionRepo.countDistinctRunsByReasonSince(signalId, "BROKER_ERROR",
                                clock.instant().minus(Duration.ofHours(brokerAttemptWindowHours)))
                        : 0;
                if (signalId != null && failedRuns >= maxBrokerAttempts) {
                    // Same terminal intent as place-entry's cap: after maxBrokerAttempts failed
                    // RUNS stop attempting. place-entry expresses "terminal" by flipping its
                    // signal to REJECTED; a tranche has no own signal status (the source signal is
                    // long ACCEPTED), so the terminal outcome here is simply "no further
                    // placement" plus an auditable decision row.
                    decisionRepo.insert(new ExecutorDecision(null, signalId, symbol, false,
                            "MAX_BROKER_ATTEMPTS", List.of(),
                            "broker attempt cap reached: " + failedRuns + "/" + maxBrokerAttempts,
                            null, runId, null));
                    return ResponseEntity.ok(Map.of("output",
                            Map.of("placed", false, "reason", "MAX_BROKER_ATTEMPTS")));
                }
                BracketRequest req = new BracketRequest(symbol, position.side(), trancheQty, pxRounded,
                        brokerStopResult.price(), null, clientRef, null);
                placed = gateway.placeBracket(connection, req);
            }
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, false,
                    "BROKER_ERROR", List.of(), "broker call failed: " + e.getMessage(), null, runId, null));
            return ResponseEntity.ok(Map.of("output",
                    Map.of("placed", false, "reason", "BROKER_ERROR", "error", e.getMessage())));
        }

        String brokerOrderId = placed.bracketId();

        try {
            // INTENDED totals, for the notification only — the tranche-2 limit is merely WORKING
            // at this point, so the broker holds none of `trancheQty` yet.
            BigDecimal intendedQty = position.qty().add(trancheQty);
            // Weighted recompute from submitted (limit) prices — intentionally not broker-basis.
            BigDecimal intendedEntry = position.qty().multiply(position.entryPrice())
                    .add(trancheQty.multiply(pxRounded))
                    .divide(intendedQty, 6, RoundingMode.HALF_UP);

            // The BOOK keeps the held qty/entry_price untouched: `qty` means shares HELD (see
            // ExecutorPosition), and booking the intended total here is what let a flatten size on
            // twice the shares the broker actually held (2026-08-06). Only the tranche flip and
            // the two leg ids are persisted now; ReconcileService.updateMaintenance() grows qty —
            // and converges entry_price to the real post-add broker basis — once the fill lands.
            positionRepo.updateTranche2(position.id(), position.qty(), position.entryPrice(),
                    brokerOrderId, placed.stopLegId());

            try {
                decisionRepo.insert(new ExecutorDecision(null, position.sourceSignalId(), symbol, true,
                        null, List.of(), "tranche 2 added: " + t2.reason(), brokerOrderId, runId, null));

                ObjectNode trancheOrderJson = mapper.createObjectNode();
                trancheOrderJson.put("type", "limit_bracket");
                trancheOrderJson.put("tranche", 2);
                trancheOrderJson.put("qty", trancheQty);
                trancheOrderJson.put("limit_price", pxRounded);
                trancheOrderJson.put("stop_price", stopRounded);
                trancheOrderJson.put("broker_stop", brokerStopResult.price());
                trancheOrderJson.put("broker_stop_buffer_atr", brokerStopBufferAtr);
                trancheOrderJson.put("broker_stop_clamped", brokerStopResult.clamped());
                trancheOrderJson.put("broker_stop_capped", brokerStopResult.capped());
                trancheOrderJson.put("stop_basis", sizing.stopBasis());
                trancheOrderJson.put("r_per_share", sizing.rPerShare());
                trancheOrderJson.put("position_risk", sizing.newRiskAccountCcy());
                trancheOrderJson.put("position_risk_broker", positionRiskBroker(position.side(),
                        trancheQty, pxRounded, brokerStopResult.price(), ctx.fxToAccount()));
                trancheOrderJson.put("qty_notional", sizing.qtyNotional());
                trancheOrderJson.put("qty_risk", sizing.qtyRisk());
                trancheOrderJson.put("sizing_basis", sizing.sizingBasis());
                trancheOrderJson.put("reject_cause",
                        sizing.rejectCause() == null ? null : sizing.rejectCause().name());
                trancheOrderJson.put("risk_pct", riskPct);
                trancheOrderJson.put("atr_short", ctx.atrShort());
                trancheOrderJson.put("atr_effective", ctx.atrEff());
                trancheOrderJson.put("position_id", position.id());
                logAddTrancheDecision(runId, position, ctx, ctx.price(), pxRounded, bounds,
                        sizing, trancheOrderJson);
            } catch (RuntimeException e) {
                // Position tranche update is durably persisted — the order is managed. Only the
                // accepted-audit row is missing; log it, but do not flip the response into a
                // false ORPHANED_ORDER (that would contradict persisted state).
                log.error("accepted-audit decisionRepo.insert failed for signal {} position {} "
                                + "broker order {}: {}",
                        position.sourceSignalId(), position.id(), brokerOrderId, e.getMessage(), e);
            }

            executorNotifier.notifyTranche2(position, trancheQty, pxRounded, intendedQty,
                    intendedEntry, t2.reason(), connection);

            return ResponseEntity.ok(Map.of("output", Map.of(
                    "placed", true,
                    "qty", trancheQty,
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
