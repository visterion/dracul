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

        String source = body.path("source").asString("injected");
        String agentVersion = nullableString(body, "agent_version");
        String symbol = nullableString(body, "symbol");
        String direction = nullableString(body, "direction");
        String mechanism = nullableString(body, "mechanism");
        String horizon = nullableString(body, "horizon");

        var signal = new ExecutorSignal(signalId, source, agentVersion, symbol, direction,
                confidence, mechanism, killCriteria, horizon, referencePrice, "PENDING", null);
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
