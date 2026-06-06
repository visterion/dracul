package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.wikipedia.WikipediaSp500Adapter;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
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
@ConditionalOnProperty(value = "dracul.strigoi.index.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-index")
public class StrigoiIndexWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiIndexWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final WikipediaSp500Adapter wikipedia;
    private final IndexScreener screener;
    private final PreyRepository preyRepo;
    private final int defaultLookback;

    public StrigoiIndexWebhookController(
            @Value("${dracul.strigoi.index.webhook-token}") String token,
            WikipediaSp500Adapter wikipedia,
            IndexScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.index.lookback-days:30}") int defaultLookback) {
        this.verifier = new BearerTokenVerifier(token);
        this.wikipedia = wikipedia;
        this.screener = screener;
        this.preyRepo = preyRepo;
        this.defaultLookback = defaultLookback;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        int lookback = defaultLookback;
        if (body.get("input") instanceof Map<?, ?> input
                && input.get("lookback_days") instanceof Number n) {
            lookback = Math.max(1, Math.min(90, n.intValue()));
        }
        var rows = wikipedia.recentConstituents();
        var candidates = screener.screen(rows, lookback);
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
            log.warn("strigoi-index run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode preyNode = body.path("output").path("prey");
        if (!preyNode.isArray() || preyNode.isEmpty()) {
            log.info("strigoi-index run {} produced no prey", runId);
            return ResponseEntity.noContent().build();
        }

        var prey = new ArrayList<Prey>();
        var nowIso = java.time.Instant.now().toString();
        for (JsonNode p : preyNode) {
            String symbol = p.path("symbol").asText("");
            if (symbol.isBlank()) continue;   // an addition with no tradeable ticker is not persistable
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            prey.add(new Prey(
                    UUID.randomUUID().toString(),
                    symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText("INDEX_INCLUSION"),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText("1m"),
                    "strigoi-index",
                    nowIso
            ));
        }
        if (prey.isEmpty()) {
            log.info("strigoi-index run {} produced only blank-symbol prey — nothing persisted", runId);
            return ResponseEntity.noContent().build();
        }
        preyRepo.insertAll(prey);
        log.info("strigoi-index run {} persisted {} prey", runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
