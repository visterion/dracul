package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.news.NewsEventType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.daywalker.enabled", havingValue = "true")
@RequestMapping("/api/daywalker")
public class DaywalkerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerWebhookController.class);

    private final BearerTokenVerifier verifier;
    private final DaywalkerEventEngine engine;
    private final DaywalkerCompletionService completionService;
    private final HiveMemResearchService memory;
    private final long priorMemoryBudgetMs;

    public DaywalkerWebhookController(
            @Value("${dracul.daywalker.webhook-token}") String token,
            DaywalkerEventEngine engine,
            DaywalkerCompletionService completionService,
            HiveMemResearchService memory,
            @Value("${dracul.daywalker.prior-memory-budget-ms:2000}") long priorMemoryBudgetMs) {
        this.verifier = new BearerTokenVerifier(token);
        this.engine = engine;
        this.completionService = completionService;
        this.memory = memory;
        this.priorMemoryBudgetMs = priorMemoryBudgetMs;
    }

    /** Event-source webhook: deterministic detection over the watchlist. */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> events(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Instant now = parseInstant(body.get("now"), Instant.now());
        Instant since = parseInstant(body.get("since"), null);

        // W3: prior_memory is folded in strictly AFTER detect() returns -- the deterministic
        // detector must never see memory. Task 11 (spec §11): wall-clock-only short-circuit
        // shared across ALL emitted events in this invocation, not per-event -- searchForInput
        // never throws (degrades to List.of() internally), so there is no exception to catch
        // here; once the budget is spent, remaining events simply skip the call.
        List<TriggerEvent> events = engine.detect(since, now);
        long priorMemoryDeadline = System.nanoTime() + priorMemoryBudgetMs * 1_000_000L;
        var payloads = new ArrayList<Map<String, Object>>();
        for (TriggerEvent ev : events) {
            Map<String, Object> payload = new LinkedHashMap<>(ev.toEventPayload());
            List<Map<String, Object>> priorMemory = System.nanoTime() < priorMemoryDeadline
                    ? memory.searchForInput(ev.symbol(), 3).stream()
                            .map(h -> Map.<String, Object>of("summary", h.summary(), "content", h.content()))
                            .toList()
                    : List.of();
            payload.put("prior_memory", priorMemory);
            payloads.add(payload);
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
                confidence, runId, positionId, mapEventType(o));
        return ResponseEntity.noContent().build();
    }

    /**
     * Wire mapping for the OPTIONAL LLM {@code event_type} (T1.3, spec §4.3):
     * missing/blank/unknown -> null; "none" -> null (not material); "other" -> literal
     * "other"; taxonomy wire values -> persisted verbatim. Mapping deliberately lives in
     * the controller (R1-m4) so persistAssessment stays a plain pass-through.
     */
    private static String mapEventType(JsonNode output) {
        JsonNode n = output.path("event_type");
        if (!n.isTextual()) return null;
        String raw = n.asText();
        if (raw.isBlank() || "none".equals(raw)) return null;
        if ("other".equals(raw)) return "other";
        return NewsEventType.fromWire(raw).map(NewsEventType::wireValue).orElse(null);
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
