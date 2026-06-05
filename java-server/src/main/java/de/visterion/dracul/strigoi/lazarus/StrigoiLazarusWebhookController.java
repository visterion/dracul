package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.finnhub.FinnhubFundamentalsAdapter;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
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

import java.util.*;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-lazarus")
public class StrigoiLazarusWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiLazarusWebhookController.class);
    private static final String USER = "default";

    private final BearerTokenVerifier verifier;
    private final WatchlistRepository watchlist;
    private final FinnhubFundamentalsAdapter fundamentals;
    private final LazarusScreener screener;
    private final PreyRepository preyRepo;
    private final double maxAboveLow;
    private final double maxDebtEquity;

    public StrigoiLazarusWebhookController(
            @Value("${dracul.strigoi.lazarus.webhook-token}") String token,
            WatchlistRepository watchlist,
            FinnhubFundamentalsAdapter fundamentals,
            LazarusScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.lazarus.max-above-low:0.10}") double maxAboveLow,
            @Value("${dracul.strigoi.lazarus.max-debt-equity:3.0}") double maxDebtEquity) {
        this.verifier = new BearerTokenVerifier(token);
        this.watchlist = watchlist;
        this.fundamentals = fundamentals;
        this.screener = screener;
        this.preyRepo = preyRepo;
        this.maxAboveLow = maxAboveLow;
        this.maxDebtEquity = maxDebtEquity;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        var items = watchlist.findAllByUser(USER);
        var raws = new ArrayList<LazarusRaw>();
        for (WatchlistItem item : items) {
            raws.add(new LazarusRaw(
                    item.ticker(), item.companyName(),
                    item.currentPrice(), fundamentals.basicFinancials(item.ticker())));
        }
        var candidates = screener.screen(raws, maxAboveLow, maxDebtEquity);
        return ResponseEntity.ok(Map.of("output", Map.of("candidates", candidates)));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        // Vistierie's successful agent-run status is "done"; "succeeded" kept defensively.
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("strigoi-lazarus run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode preyNode = body.path("output").path("prey");
        if (!preyNode.isArray() || preyNode.isEmpty()) {
            log.info("strigoi-lazarus run {} produced no prey", runId);
            return ResponseEntity.noContent().build();
        }

        var prey = new ArrayList<Prey>();
        var nowIso = java.time.Instant.now().toString();
        for (JsonNode p : preyNode) {
            String symbol = p.path("symbol").asText("");
            if (symbol.isBlank()) continue;   // a prey with no tradeable ticker is not persistable
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            prey.add(new Prey(
                    UUID.randomUUID().toString(),
                    symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText("QUALITY_52W_LOW"),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText("12m"),
                    "strigoi-lazarus",
                    nowIso
            ));
        }
        if (prey.isEmpty()) {
            log.info("strigoi-lazarus run {} produced only blank-symbol prey — nothing persisted", runId);
            return ResponseEntity.noContent().build();
        }
        preyRepo.insertAll(prey);
        log.info("strigoi-lazarus run {} persisted {} prey", runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
