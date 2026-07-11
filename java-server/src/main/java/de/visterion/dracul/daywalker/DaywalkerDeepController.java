package de.visterion.dracul.daywalker;

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

/**
 * Completion webhook for the {@code daywalker-deep} escalation agent: re-assesses a
 * single low-confidence CRITICAL Daywalker call and feeds the result back through
 * {@link DaywalkerCompletionService#persistAssessment} with {@code fromEscalation=true}
 * (loop guard — a downgraded/confirmed severity never re-triggers another escalation).
 */
@RestController
@ConditionalOnProperty(value = "dracul.daywalker-deep.enabled", havingValue = "true")
@RequestMapping("/api/daywalker-deep")
public class DaywalkerDeepController {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerDeepController.class);

    private final BearerTokenVerifier verifier;
    private final DaywalkerCompletionService completionService;

    public DaywalkerDeepController(
            @Value("${dracul.daywalker-deep.webhook-token}") String token,
            DaywalkerCompletionService completionService) {
        this.verifier = new BearerTokenVerifier(token);
        this.completionService = completionService;
    }

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
            log.warn("daywalker-deep run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }

        JsonNode o = body.path("output");
        String symbol = o.path("symbol").asText("");
        String triggerType = o.path("trigger_type").asText("");
        if (symbol.isBlank() || triggerType.isBlank()) {
            log.warn("daywalker-deep run {} missing symbol/trigger_type — skipping", runId);
            return ResponseEntity.noContent().build();
        }
        BigDecimal confidence = o.path("confidence").isNumber()
                ? new BigDecimal(o.path("confidence").asText()) : null;
        // Echoed back from the trigger input (see prompts/daywalker-deep.md) so the
        // follow-up resolves against the same owner set as the original assessment.
        String positionId = o.path("position_id").isTextual()
                ? o.path("position_id").asText() : null;

        completionService.persistAssessment(symbol, triggerType,
                o.path("severity").asText("INFO"), o.path("thesis").asText(""),
                confidence, runId, positionId, true);
        return ResponseEntity.noContent().build();
    }
}
