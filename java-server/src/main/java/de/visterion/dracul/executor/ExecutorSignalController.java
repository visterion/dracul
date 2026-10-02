package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator injection seam: post an executor signal (from an external analysis
 * pipeline or a human) and list signals awaiting execution evaluation.
 */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor")
public class ExecutorSignalController {

    /** Same upper bound as {@link de.visterion.dracul.webhook.PreyMapper}: NUMERIC(18,6) in the
     *  `prey`/signal tables overflows well before this. */
    private static final BigDecimal MAX_KILL_CLOSE_BELOW = new BigDecimal("1e12");

    private final ExecutorSignalRepository repo;

    public ExecutorSignalController(ExecutorSignalRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/signals")
    public ResponseEntity<Map<String, Object>> inject(@RequestBody JsonNode body) {
        String signalId = body.path("signal_id").asString("");
        if (signalId.isBlank()) signalId = UUID.randomUUID().toString();

        List<String> killCriteria = new ArrayList<>();
        JsonNode killNode = body.path("kill_criteria");
        if (killNode.isArray()) {
            for (JsonNode k : killNode) killCriteria.add(k.asString(""));
        }

        JsonNode confidenceNode = body.path("confidence");
        Double confidence = confidenceNode.isNumber() ? confidenceNode.asDouble() : null;

        JsonNode referencePriceNode = body.path("reference_price");
        BigDecimal referencePrice = referencePriceNode.isNumber()
                ? new BigDecimal(referencePriceNode.asString())
                : null;

        // Optional structured kill level (V51). Same posture as the hunters' PreyMapper: only a
        // strictly positive, finite JSON number below the NUMERIC(18,6) overflow bound is kept,
        // anything else (including a non-finite or overflowing value such as 1e400/1e13) means
        // "no level" rather than throwing out of this seam.
        JsonNode killCloseBelowNode = body.path("kill_close_below");
        BigDecimal killCloseBelow = null;
        if (killCloseBelowNode.isNumber() && Double.isFinite(killCloseBelowNode.asDouble())) {
            try {
                BigDecimal candidate = new BigDecimal(killCloseBelowNode.asString());
                if (candidate.signum() > 0 && candidate.compareTo(MAX_KILL_CLOSE_BELOW) < 0) {
                    killCloseBelow = candidate;
                }
            } catch (NumberFormatException ignored) {
                // stays null, same as any other unusable value
            }
        }

        String source = body.path("source").asString("injected");
        String agentVersion = body.path("agent_version").asString("");
        if (agentVersion.isBlank()) agentVersion = "operator";
        String symbol = nullableString(body, "symbol");
        String direction = nullableString(body, "direction");
        String mechanism = nullableString(body, "mechanism");
        String horizon = nullableString(body, "horizon");

        var signal = new ExecutorSignal(signalId, source, agentVersion, symbol, direction,
                confidence, mechanism, killCriteria, horizon, referencePrice, "PENDING", null, null,
                null, null, null, killCloseBelow);
        repo.insert(signal);

        return ResponseEntity.ok(Map.of("signal_id", signalId, "status", "PENDING"));
    }

    @GetMapping("/signals")
    public List<ExecutorSignal> pending() {
        return repo.findPending(50);
    }

    private static String nullableString(JsonNode body, String field) {
        JsonNode v = body.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asString(null);
    }
}
