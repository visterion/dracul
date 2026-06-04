package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.yahoo.YahooEarningsAdapter;
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
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-echo")
public class StrigoiEchoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiEchoWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final YahooEarningsAdapter yahoo;
    private final EchoPeadScreener screener;
    private final PreyRepository preyRepo;

    public StrigoiEchoWebhookController(
            @Value("${dracul.strigoi.echo.webhook-token}") String token,
            YahooEarningsAdapter yahoo,
            EchoPeadScreener screener,
            PreyRepository preyRepo) {
        this.verifier = new BearerTokenVerifier(token);
        this.yahoo = yahoo;
        this.screener = screener;
        this.preyRepo = preyRepo;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        int lookback = 7;
        if (body.get("input") instanceof Map<?, ?> input
                && input.get("lookback_days") instanceof Number n) {
            lookback = Math.max(1, Math.min(30, n.intValue()));
        }
        var to = LocalDate.now();
        var from = to.minusDays(lookback);
        var events = yahoo.recentEarnings(from, to);
        var candidates = screener.screen(events);
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
            log.warn("strigoi-echo run {} status={} — acknowledging without persisting",
                    runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode preyNode = body.path("output").path("prey");
        if (!preyNode.isArray() || preyNode.isEmpty()) {
            log.info("strigoi-echo run {} produced no prey", runId);
            return ResponseEntity.noContent().build();
        }

        var prey = new ArrayList<Prey>();
        var nowIso = java.time.Instant.now().toString();
        for (JsonNode p : preyNode) {
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            prey.add(new Prey(
                    UUID.randomUUID().toString(),
                    p.path("symbol").asText(""),
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText("PEAD"),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText("3m"),
                    "strigoi-echo",
                    nowIso
            ));
        }
        preyRepo.insertAll(prey);
        log.info("strigoi-echo run {} persisted {} prey", runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
