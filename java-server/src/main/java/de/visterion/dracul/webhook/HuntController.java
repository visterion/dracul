package de.visterion.dracul.webhook;

import de.visterion.dracul.prey.PreyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Base for the 6 prey-producing hunters. Subclasses declare their own
 *  fetch @PostMapping (the sub-path varies — e.g. insider uses /tools/fetch-clusters)
 *  and delegate to {@link #handleFetch}. The /complete endpoint is uniform and owned here. */
public abstract class HuntController {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BearerTokenVerifier verifier;
    private final PreyRepository preyRepo;
    private final PreyMapper preyMapper = new PreyMapper();

    protected HuntController(String token, PreyRepository preyRepo) {
        this.verifier = new BearerTokenVerifier(token);
        this.preyRepo = preyRepo;
    }

    protected abstract String agentName();
    protected abstract List<?> hunt(Map<String, Object> input);
    protected abstract String defaultAnomalyType();
    protected String defaultHorizon() { return "3m"; }
    protected boolean skipBlankSymbol() { return false; }

    /** Key used in the fetch response envelope, e.g. "candidates" or "clusters". */
    protected String fetchOutputKey() { return "candidates"; }

    /** Clamp lookback_days from the tool input. */
    protected int lookbackDays(Map<String, Object> body, int def, int min, int max) {
        if (body.get("input") instanceof Map<?, ?> in
                && in.get("lookback_days") instanceof Number n) {
            return Math.max(min, Math.min(max, n.intValue()));
        }
        return def;
    }

    /** Subclasses call this from their own @PostMapping("/tools/...") method. */
    protected ResponseEntity<Map<String, Object>> handleFetch(String auth, Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("output", Map.of(fetchOutputKey(), hunt(body))));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("{} run {} status={} — acknowledging without persisting", agentName(), runId, status);
            return ResponseEntity.noContent().build();
        }
        var prey = preyMapper.map(body.path("output").path("prey"), agentName(),
                defaultAnomalyType(), defaultHorizon(), skipBlankSymbol());
        if (prey.isEmpty()) {
            log.info("{} run {} produced no persistable prey", agentName(), runId);
            return ResponseEntity.noContent().build();
        }
        preyRepo.insertAll(prey);
        log.info("{} run {} persisted {} prey", agentName(), runId, prey.size());
        return ResponseEntity.noContent().build();
    }
}
