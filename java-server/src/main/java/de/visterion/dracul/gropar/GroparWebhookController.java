package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import de.visterion.dracul.watchlist.PositionRisk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
@RequestMapping("/api/gropar")
public class GroparWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GroparWebhookController.class);
    private static final String HOLD = "HOLD";

    private final BearerTokenVerifier verifier;
    private final WatchlistRepository watchlistRepo;
    private final VerdictRepository verdictRepo;
    private final MarketDataPort marketData;
    private final ExitSignalRepository exitSignalRepo;
    private final TelegramNotifier telegram;
    private final ExitIndicatorService indicatorService;
    private final RiskMetricsService riskService;
    private final ToolFetchCache cache;

    private final int historyDays;
    private final double profitTargetPct;
    private final double stopLossPct;
    private final long fetchThrottleMs;

    public GroparWebhookController(
            @Value("${dracul.gropar.webhook-token}") String token,
            WatchlistRepository watchlistRepo,
            VerdictRepository verdictRepo,
            MarketDataPort marketData,
            ExitSignalRepository exitSignalRepo,
            TelegramNotifier telegram,
            ExitIndicatorService indicatorService,
            RiskMetricsService riskService,
            ToolFetchCache cache,
            @Value("${dracul.gropar.history-days:260}") int historyDays,
            @Value("${dracul.gropar.profit-target-pct:40}") double profitTargetPct,
            @Value("${dracul.gropar.stop-loss-pct:15}") double stopLossPct,
            @Value("${dracul.gropar.fetch-throttle-ms:250}") long fetchThrottleMs) {

        this.verifier = new BearerTokenVerifier(token);
        this.watchlistRepo = watchlistRepo;
        this.verdictRepo = verdictRepo;
        this.marketData = marketData;
        this.exitSignalRepo = exitSignalRepo;
        this.telegram = telegram;
        this.indicatorService = indicatorService;
        this.riskService = riskService;
        this.cache = cache;
        this.historyDays = historyDays;
        this.profitTargetPct = profitTargetPct;
        this.stopLossPct = stopLossPct;
        this.fetchThrottleMs = fetchThrottleMs;
    }

    /** Tool callback: returns all held positions enriched with exit indicators. */
    @PostMapping("/tools/fetch-held-positions")
    public ResponseEntity<Map<String, Object>> fetchHeldPositions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Map<String, Object> out = cache.get("fetch_held_positions", "all", () -> {
            var held = watchlistRepo.findAll().stream()
                    .filter(this::isHeld)
                    .toList();

            var riskByItem = watchlistRepo.positionRiskByItemId();

            var views = new ArrayList<HeldPositionView>();
            for (WatchlistItem item : held) {
                try {
                    if (fetchThrottleMs > 0 && !views.isEmpty()) {
                        try { Thread.sleep(fetchThrottleMs); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    var bars = marketData.dailyOhlcHistory(item.ticker(), historyDays);

                    // Resolve verdict data for horizon / thesis
                    String verdictCreatedAt = null;
                    String horizon = null;
                    Map<String, Object> thesis = null;

                    if (item.verdictId() != null) {
                        var detail = verdictRepo.findDetailById(item.verdictId());
                        if (detail.isPresent()) {
                            var vd = detail.get();
                            verdictCreatedAt = vd.createdAt();
                            horizon = vd.horizon();
                            thesis = new HashMap<>();
                            thesis.put("summary", vd.summary());
                            thesis.put("signals", vd.signals());
                            thesis.put("risks", vd.risks());
                            thesis.put("anomalyTypes", vd.anomalyTypes());
                            thesis.put("horizon", vd.horizon());
                        }
                    }

                    var ind = indicatorService.compute(bars,
                            BigDecimal.valueOf(item.entryPrice()),
                            verdictCreatedAt, horizon);

                    PositionRisk pr = riskByItem.get(item.id());
                    BigDecimal storedStop = pr == null ? null : pr.initialStop();
                    LocalDate entryDate = null;
                    if (pr != null && pr.entryDate() != null) {
                        try {
                            entryDate = LocalDate.parse(pr.entryDate());
                        } catch (java.time.format.DateTimeParseException e) {
                            log.warn("gropar: unparseable entry_date '{}' for {} — ignoring",
                                    pr.entryDate(), item.ticker());
                        }
                    }
                    var risk = riskService.compute(bars,
                            BigDecimal.valueOf(item.entryPrice()), entryDate, storedStop,
                            ind.atr(), ind.atrAvailable());
                    if (risk.derivedNow() && risk.initialStop() != null) {
                        try {
                            watchlistRepo.updateInitialStop(item.id(), risk.initialStop());
                        } catch (Exception e) {
                            log.warn("gropar: failed to freeze initial stop for {}: {}",
                                    item.ticker(), e.getMessage());
                        }
                    }

                    // Slice 2a: persist the per-position risk snapshot for the
                    // morning report. active_stop = max(initial, chandelier); the
                    // morning report reads this (0 market-data calls at report time).
                    BigDecimal activeStop = null;
                    if (risk.initialStopAvailable() && risk.initialStop() != null
                            && ind.chandelierStop() != null) {
                        activeStop = risk.initialStop().max(ind.chandelierStop());
                    } else if (risk.initialStopAvailable() && risk.initialStop() != null) {
                        activeStop = risk.initialStop();
                    } else if (ind.chandelierStop() != null) {
                        activeStop = ind.chandelierStop();
                    }
                    BigDecimal nextTarget2r = null;
                    if (risk.rAvailable() && risk.r() != null) {
                        nextTarget2r = BigDecimal.valueOf(item.entryPrice())
                                .add(risk.r().multiply(BigDecimal.valueOf(2)));
                    }
                    BigDecimal currentClose = ind.currentClose();
                    BigDecimal atr = ind.atrAvailable() ? ind.atr() : null;
                    try {
                        watchlistRepo.updateRiskSnapshot(item.id(), activeStop,
                                nextTarget2r, currentClose, atr, Instant.now());
                    } catch (Exception e) {
                        log.warn("gropar: failed to persist risk snapshot for {}: {}",
                                item.ticker(), e.getMessage());
                    }

                    // Build fired rules: copy from indicator, then add controller-side rules
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

                    views.add(new HeldPositionView(
                            item.id(),
                            item.ticker(),
                            item.companyName(),
                            item.entryPrice(),
                            item.shareCount(),
                            item.currentPrice(),
                            ind,
                            risk,
                            firedRules,
                            thesis));

                } catch (MarketDataException e) {
                    log.warn("gropar: market data unavailable for {} — skipping: {}",
                            item.ticker(), e.getMessage());
                }
            }
            return Map.of("output", Map.of("positions", views));
        });
        return ResponseEntity.ok(out);
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

        // Build positionId → owner map for held items across all users
        Map<String, String> ownerByPosition = new HashMap<>();
        for (WatchlistItem item : watchlistRepo.findAll()) {
            if (isHeld(item)) {
                ownerByPosition.put(item.id(), item.owner());
            }
        }

        for (JsonNode node : signals) {
            String positionId   = node.path("position_id").asText("");
            String symbol       = node.path("symbol").asText("");
            String action       = node.path("action").asText(HOLD);
            String thesisStatus = node.path("thesis_status").asText(null);
            String rationale    = node.path("rationale").asText(null);

            Double confidence  = nullableDouble(node, "confidence");
            Double gainLossPct = nullableDouble(node, "gain_loss_pct");

            String owner = ownerByPosition.get(positionId);
            if (owner == null) {
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
                    positionId,
                    symbol,
                    action,
                    firedRules,
                    gainLossPct,
                    thesisStatus,
                    rationale,
                    confidence,
                    runId,
                    Instant.now().toString());

            exitSignalRepo.insert(signal, owner);

            if (!HOLD.equals(action)) {
                // "EXIT" is the triggerType label; owner is prefixed so the single
                // operator channel stays attributable across users.
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

    private boolean isHeld(WatchlistItem item) {
        return "HELD".equals(item.tag())
                && item.entryPrice() != null
                && item.shareCount() != null;
    }
}
