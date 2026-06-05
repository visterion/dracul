package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.edgar.EdgarMergerAdapter;
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

import java.time.LocalDate;
import java.util.*;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.merger.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-merger")
public class StrigoiMergerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiMergerWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final EdgarMergerAdapter edgar;
    private final MergerScreener screener;
    private final PreyRepository preyRepo;
    private final int defaultLookback;

    public StrigoiMergerWebhookController(
            @Value("${dracul.strigoi.merger.webhook-token}") String token,
            EdgarMergerAdapter edgar,
            MergerScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.merger.lookback-days:45}") int defaultLookback) {
        this.verifier = new BearerTokenVerifier(token);
        this.edgar = edgar;
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
            lookback = Math.max(1, Math.min(120, n.intValue()));
        }
        var to = LocalDate.now();
        var from = to.minusDays(lookback);
        var filings = edgar.recentDeals(from, to);
        var candidates = screener.screen(filings);
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
            log.warn("strigoi-merger run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode preyNode = body.path("output").path("prey");
        if (!preyNode.isArray() || preyNode.isEmpty()) {
            log.info("strigoi-merger run {} produced no prey", runId);
            return ResponseEntity.noContent().build();
        }

        var prey = new ArrayList<Prey>();
        var nowIso = java.time.Instant.now().toString();
        for (JsonNode p : preyNode) {
            String symbol = p.path("symbol").asText("");
            if (symbol.isBlank()) continue;   // a target with no tradeable ticker is not persistable
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            prey.add(new Prey(
                    UUID.randomUUID().toString(),
                    symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText("MERGER_ARB"),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText("3m"),
                    "strigoi-merger",
                    nowIso
            ));
        }
        if (prey.isEmpty()) {
            log.info("strigoi-merger run {} produced only blank-symbol prey — nothing persisted", runId);
            return ResponseEntity.noContent().build();
        }
        preyRepo.insertAll(prey);
        log.info("strigoi-merger run {} persisted {} prey", runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
