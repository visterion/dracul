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

import java.math.BigDecimal;
import java.time.Instant;
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
    private static final String USER = "default";
    private static final String HOLD = "HOLD";

    private final BearerTokenVerifier verifier;
    private final WatchlistRepository watchlistRepo;
    private final VerdictRepository verdictRepo;
    private final MarketDataPort marketData;
    private final ExitSignalRepository exitSignalRepo;
    private final TelegramNotifier telegram;
    private final ExitIndicatorService indicatorService;
    private final ToolFetchCache cache;

    private final int historyDays;
    private final double profitTargetPct;
    private final double stopLossPct;

    public GroparWebhookController(
            @Value("${dracul.gropar.webhook-token}") String token,
            WatchlistRepository watchlistRepo,
            VerdictRepository verdictRepo,
            MarketDataPort marketData,
            ExitSignalRepository exitSignalRepo,
            TelegramNotifier telegram,
            ExitIndicatorService indicatorService,
            ToolFetchCache cache,
            @Value("${dracul.gropar.history-days:260}") int historyDays,
            @Value("${dracul.gropar.profit-target-pct:40}") double profitTargetPct,
            @Value("${dracul.gropar.stop-loss-pct:15}") double stopLossPct) {

        this.verifier = new BearerTokenVerifier(token);
        this.watchlistRepo = watchlistRepo;
        this.verdictRepo = verdictRepo;
        this.marketData = marketData;
        this.exitSignalRepo = exitSignalRepo;
        this.telegram = telegram;
        this.indicatorService = indicatorService;
        this.cache = cache;
        this.historyDays = historyDays;
        this.profitTargetPct = profitTargetPct;
        this.stopLossPct = stopLossPct;
    }

    /** Tool callback: returns all held positions enriched with exit indicators. */
    @PostMapping("/tools/fetch-held-positions")
    public ResponseEntity<Map<String, Object>> fetchHeldPositions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Map<String, Object> out = cache.get("fetch_held_positions", "default", () -> {
            var held = watchlistRepo.findAllByUser(USER).stream()
                    .filter(this::isHeld)
                    .toList();

            var views = new ArrayList<HeldPositionView>();
            for (WatchlistItem item : held) {
                try {
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

                    views.add(new HeldPositionView(
                            item.ticker(),
                            item.companyName(),
                            item.entryPrice(),
                            item.shareCount(),
                            item.currentPrice(),
                            ind,
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

        // Build symbol → watchlist-item-id map for held items
        Map<String, String> itemIdBySymbol = new HashMap<>();
        for (WatchlistItem item : watchlistRepo.findAllByUser(USER)) {
            if (isHeld(item)) {
                itemIdBySymbol.put(item.ticker(), item.id());
            }
        }

        for (JsonNode node : signals) {
            String symbol       = node.path("symbol").asText("");
            String action       = node.path("action").asText(HOLD);
            String thesisStatus = node.path("thesis_status").asText(null);
            String rationale    = node.path("rationale").asText(null);

            Double confidence  = nullableDouble(node, "confidence");
            Double gainLossPct = nullableDouble(node, "gain_loss_pct");

            if (!itemIdBySymbol.containsKey(symbol)) {
                log.warn("gropar: signal for unknown/non-held symbol {} — persisting without watchlist link", symbol);
            }

            var firedRules = new ArrayList<String>();
            JsonNode rulesNode = node.path("fired_rules");
            if (rulesNode.isArray()) {
                for (JsonNode r : rulesNode) firedRules.add(r.asText());
            }

            var signal = new ExitSignal(
                    UUID.randomUUID().toString(),
                    itemIdBySymbol.get(symbol),
                    symbol,
                    action,
                    firedRules,
                    gainLossPct,
                    thesisStatus,
                    rationale,
                    confidence,
                    runId,
                    Instant.now().toString());

            exitSignalRepo.insert(signal, USER);

            if (!HOLD.equals(action)) {
                // "EXIT" is the triggerType label shown in the Telegram alert
                telegram.notifyAlert(symbol, "EXIT", action, rationale);
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
