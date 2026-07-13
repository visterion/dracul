package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
@RequestMapping("/api/gropar")
public class GroparWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GroparWebhookController.class);
    private static final String HOLD = "HOLD";

    private final BearerTokenVerifier verifier;
    private final HeldPositionService heldPositionService;
    private final AgoraMarketData marketData;
    private final ExitSignalRepository exitSignalRepo;
    private final TelegramNotifier telegram;
    private final GroparExitIndicators indicatorService;
    private final RiskMetricsService riskService;
    private final ToolFetchCache cache;
    private final ObjectMapper mapper;

    private final String connection;
    private final String owner;
    private final int historyDays;
    private final double profitTargetPct;
    private final double stopLossPct;
    private final long fetchThrottleMs;

    public GroparWebhookController(
            @Value("${dracul.gropar.webhook-token}") String token,
            HeldPositionService heldPositionService,
            AgoraMarketData marketData,
            ExitSignalRepository exitSignalRepo,
            TelegramNotifier telegram,
            GroparExitIndicators indicatorService,
            RiskMetricsService riskService,
            ToolFetchCache cache,
            ObjectMapper mapper,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.gropar.history-days:260}") int historyDays,
            @Value("${dracul.gropar.profit-target-pct:40}") double profitTargetPct,
            @Value("${dracul.gropar.stop-loss-pct:15}") double stopLossPct,
            @Value("${dracul.gropar.fetch-throttle-ms:250}") long fetchThrottleMs) {

        this.verifier = new BearerTokenVerifier(token);
        this.heldPositionService = heldPositionService;
        this.marketData = marketData;
        this.exitSignalRepo = exitSignalRepo;
        this.telegram = telegram;
        this.indicatorService = indicatorService;
        this.riskService = riskService;
        this.cache = cache;
        this.mapper = mapper;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.historyDays = historyDays;
        this.profitTargetPct = profitTargetPct;
        this.stopLossPct = stopLossPct;
        this.fetchThrottleMs = fetchThrottleMs;
    }

    /** Tool callback: returns all held depot positions (depot ⨝ context) enriched with exit indicators. */
    @PostMapping("/tools/fetch-held-positions")
    public ResponseEntity<Map<String, Object>> fetchHeldPositions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Map<String, Object> out = cache.get("fetch_held_positions", "all", () -> {
            var positions = heldPositionService.openPositions(connection);

            var views = new ArrayList<HeldPositionView>();
            for (HeldPosition hp : positions) {
                try {
                    if (fetchThrottleMs > 0 && !views.isEmpty()) {
                        try { Thread.sleep(fetchThrottleMs); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    var bars = marketData.dailyOhlcHistory(hp.symbol(), historyDays);

                    // TA-only degrade: a null context (executor-opened positions, or a depot
                    // position with no matching verdict) simply yields thesis=null below --
                    // never dropped, never erroring.
                    Map<String, Object> thesis = buildThesis(hp);

                    // position_context.opened_at is the best-effort anchor for both TIME_STOP
                    // horizon-elapsed detection and the MFE/giveback peak-search window --
                    // DepotPosition carries no true fill date, so this is the same anchor the
                    // old verdict-createdAt path used. Null for context-less (TA-only) positions,
                    // same fallback behaviour as before (horizon check disabled, MFE scans all bars).
                    var ind = indicatorService.compute(hp.symbol(), bars, hp.avgPrice(), hp.openedAt(), hp.horizon());

                    var risk = riskService.compute(bars, hp.avgPrice(), parseEntryDate(hp.openedAt()),
                            hp.initialStop(), ind.atr(), ind.atrAvailable());

                    var firedRules = new ArrayList<>(ind.firedRules());
                    if (ind.gainLossPct() != null
                            && ind.gainLossPct().doubleValue() >= profitTargetPct) {
                        firedRules.add(ExitRules.PROFIT_TARGET);
                    }
                    if (ind.gainLossPct() != null
                            && ind.gainLossPct().doubleValue() <= -stopLossPct) {
                        firedRules.add(ExitRules.STOP_LOSS);
                    }
                    if (risk.initialStopBreached()) firedRules.add(ExitRules.INITIAL_STOP);
                    if (risk.givebackBreached())    firedRules.add(ExitRules.GIVEBACK);

                    var profitTargets = ScaleOutLadder.profitTargets(
                            hp.avgPrice(), risk.rAvailable() ? risk.r() : null);

                    BigDecimal currentPrice = ind.currentClose() != null ? ind.currentClose() : hp.avgPrice();

                    views.add(new HeldPositionView(
                            hp.symbol(),
                            hp.symbol(),
                            hp.symbol(), // depot positions carry no company name -- symbol is the best available label
                            hp.avgPrice().doubleValue(),
                            hp.quantity().doubleValue(),
                            currentPrice == null ? 0.0 : currentPrice.doubleValue(),
                            ind,
                            risk,
                            firedRules,
                            thesis,
                            profitTargets,
                            ScaleOutLadder.SCALE_OUT_FRACTIONS));

                } catch (MarketDataException e) {
                    log.warn("gropar: market data unavailable for {} — skipping: {}",
                            hp.symbol(), e.getMessage());
                }
            }
            return Map.of("output", Map.of("positions", views));
        });
        return ResponseEntity.ok(out);
    }

    /** Builds the thesis block from the position's stored context snapshot; null (TA-only)
     *  when the position has no open context row (null verdictId / null snapshot). */
    private Map<String, Object> buildThesis(HeldPosition hp) {
        if (hp.thesisSnapshot() == null) return null;
        Map<String, Object> thesis;
        try {
            thesis = new LinkedHashMap<>(
                    mapper.convertValue(hp.thesisSnapshot(), new TypeReference<Map<String, Object>>() {}));
        } catch (RuntimeException e) {
            log.warn("gropar: failed to parse thesis snapshot for {} — degrading to TA-only: {}",
                    hp.symbol(), e.getMessage());
            return null;
        }
        if (hp.killCriteria() != null) {
            try {
                List<String> kill = mapper.convertValue(hp.killCriteria(), new TypeReference<List<String>>() {});
                if (kill != null && !kill.isEmpty()) thesis.put("killCriteria", kill);
            } catch (RuntimeException e) {
                log.warn("gropar: failed to parse kill criteria for {} — omitting: {}",
                        hp.symbol(), e.getMessage());
            }
        }
        return thesis;
    }

    /** Completion webhook: persists LLM exit signals and fires Telegram for non-HOLD actions. */
    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        // Vistierie's successful agent-run status is "done"; "succeeded" kept defensively.
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("gropar run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }

        JsonNode signals = body.path("output").path("signals");
        if (!signals.isArray() || signals.isEmpty()) {
            log.warn("gropar run {} — no signals in output, skipping", runId);
            return ResponseEntity.noContent().build();
        }

        // Depot positions are keyed by symbol (no watchlist item id survives this source), so
        // "held" validation is a fresh symbol lookup rather than an owner map.
        Set<String> heldSymbols = heldPositionService.openPositions(connection).stream()
                .map(HeldPosition::symbol)
                .collect(Collectors.toSet());

        for (JsonNode node : signals) {
            String positionId   = node.path("position_id").asText("");
            String symbol       = node.path("symbol").asText("");
            String action       = node.path("action").asText(HOLD);
            String thesisStatus = node.path("thesis_status").asText(null);
            String rationale    = node.path("rationale").asText(null);

            Double confidence  = nullableDouble(node, "confidence");
            Double gainLossPct = nullableDouble(node, "gain_loss_pct");

            var violatedKillCriteria = new ArrayList<String>();
            JsonNode violatedNode = node.path("violated_kill_criteria");
            if (violatedNode.isArray()) {
                for (JsonNode v : violatedNode) violatedKillCriteria.add(v.asText());
            }
            if (!violatedKillCriteria.isEmpty() && rationale != null) {
                rationale = rationale + " [Verletzt: " + String.join("; ", violatedKillCriteria) + "]";
            }

            if (!heldSymbols.contains(positionId)) {
                log.warn("gropar: signal for unknown/non-held position_id {} (symbol {}) — skipping",
                        positionId, symbol);
                continue;
            }

            var firedRules = new ArrayList<String>();
            JsonNode rulesNode = node.path("fired_rules");
            if (rulesNode.isArray()) {
                for (JsonNode r : rulesNode) firedRules.add(r.asText());
            }

            var signal = new ExitSignal(
                    UUID.randomUUID().toString(),
                    null, // no watchlist item survives the depot-sourced position; symbol carries identity
                    symbol,
                    action,
                    firedRules,
                    gainLossPct,
                    thesisStatus,
                    rationale,
                    confidence,
                    runId,
                    Instant.now().toString());

            boolean fresh = exitSignalRepo.insert(signal, owner);

            if (fresh && !HOLD.equals(action)) {
                // "EXIT" is the triggerType label; owner is prefixed so downstream consumers of
                // the alert text keep the same "[owner] rationale" shape as before.
                telegram.notifyAlert(symbol, "EXIT", action,
                        "[" + owner + "] " + (rationale == null ? "" : rationale));
            }
        }

        return ResponseEntity.noContent().build();
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asDouble();
    }

    /** Best-effort entry-date anchor for RiskMetricsService's MFE peak search, parsed from
     *  {@code position_context.opened_at} (an ISO instant/date string). Null if absent or
     *  unparseable -- RiskMetricsService already falls back to scanning all bars in that case. */
    private static LocalDate parseEntryDate(String openedAt) {
        if (openedAt == null || openedAt.isBlank()) return null;
        try {
            String dateStr = openedAt.length() >= 10 ? openedAt.substring(0, 10) : openedAt;
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            log.warn("gropar: unparseable position_context.opened_at {} — MFE window unscoped: {}",
                    openedAt, e.getMessage());
            return null;
        }
    }
}
