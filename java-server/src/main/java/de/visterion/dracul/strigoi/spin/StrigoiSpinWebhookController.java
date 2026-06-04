package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.edgar.EdgarSpinoffAdapter;
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
@ConditionalOnProperty(value = "dracul.strigoi.spin.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-spin")
public class StrigoiSpinWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiSpinWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final EdgarSpinoffAdapter edgar;
    private final SpinoffScreener screener;
    private final PreyRepository preyRepo;
    private final int defaultLookback;

    public StrigoiSpinWebhookController(
            @Value("${dracul.strigoi.spin.webhook-token}") String token,
            EdgarSpinoffAdapter edgar,
            SpinoffScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.spin.lookback-days:60}") int defaultLookback) {
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
            lookback = Math.max(1, Math.min(90, n.intValue()));
        }
        var to = LocalDate.now();
        var from = to.minusDays(lookback);
        var filings = edgar.recentSpinoffs(from, to);
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
            log.warn("strigoi-spin run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode preyNode = body.path("output").path("prey");
        if (!preyNode.isArray() || preyNode.isEmpty()) {
            log.info("strigoi-spin run {} produced no prey", runId);
            return ResponseEntity.noContent().build();
        }

        var prey = new ArrayList<Prey>();
        var nowIso = java.time.Instant.now().toString();
        for (JsonNode p : preyNode) {
            String symbol = p.path("symbol").asText("");
            if (symbol.isBlank()) continue;   // spin-cos with no tradeable ticker are not persistable
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            prey.add(new Prey(
                    UUID.randomUUID().toString(),
                    symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText("SPINOFF"),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText("6m"),
                    "strigoi-spin",
                    nowIso
            ));
        }
        if (prey.isEmpty()) {
            log.info("strigoi-spin run {} produced only blank-symbol prey — nothing persisted", runId);
            return ResponseEntity.noContent().build();
        }
        preyRepo.insertAll(prey);
        log.info("strigoi-spin run {} persisted {} prey", runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
