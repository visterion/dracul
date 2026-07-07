package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
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
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.daywalker.enabled", havingValue = "true")
@RequestMapping("/api/daywalker")
public class DaywalkerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final DaywalkerEventEngine engine;
    private final DaywalkerCompletionService completionService;

    public DaywalkerWebhookController(
            @Value("${dracul.daywalker.webhook-token}") String token,
            DaywalkerEventEngine engine,
            DaywalkerCompletionService completionService) {
        this.verifier = new BearerTokenVerifier(token);
        this.engine = engine;
        this.completionService = completionService;
    }

    /** Event-source webhook: deterministic detection over the watchlist. */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> events(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Instant now = parseInstant(body.get("now"), Instant.now());
        Instant since = parseInstant(body.get("since"), null);

        var payloads = new ArrayList<Map<String, Object>>();
        for (TriggerEvent ev : engine.detect(since, now)) {
            payloads.add(ev.toEventPayload());
        }
        return ResponseEntity.ok(Map.of("events", payloads));
    }

    /** Completion webhook: persist the LLM assessment as an alert. */
    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        // Vistierie's successful agent-run status is "done" (AgentRunner); "succeeded"
        // is kept for defensive compatibility with tests/fixtures.
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("daywalker run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode o = body.path("output");
        String symbol = o.path("symbol").asText("");
        String triggerType = o.path("trigger_type").asText("");
        if (symbol.isBlank() || triggerType.isBlank()) {
            log.warn("daywalker run {} missing symbol/trigger_type — skipping", runId);
            return ResponseEntity.noContent().build();
        }
        BigDecimal confidence = o.path("confidence").isNumber()
                ? new BigDecimal(o.path("confidence").asText()) : null;
        String positionId = o.path("position_id").isTextual()
                ? o.path("position_id").asText() : null;
        completionService.persistAssessment(symbol, triggerType,
                o.path("severity").asText("INFO"), o.path("thesis").asText(""),
                confidence, runId, positionId);
        return ResponseEntity.noContent().build();
    }

    private static Instant parseInstant(Object v, Instant fallback) {
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s);
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }
}
