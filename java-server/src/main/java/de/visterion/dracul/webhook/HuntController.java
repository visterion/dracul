package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.executor.PreySignalEmitter;
import de.visterion.dracul.prey.PreyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Base for the 6 prey-producing hunters. Subclasses declare their own
 *  fetch @PostMapping (the sub-path varies — e.g. insider uses /tools/fetch-clusters)
 *  and delegate to {@link #handleFetch}. The /complete endpoint is uniform and owned here. */
public abstract class HuntController {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BearerTokenVerifier verifier;
    private final PreyRepository preyRepo;
    private final PreyMapper preyMapper = new PreyMapper();
    private final ToolFetchCache cache;

    /** Optional: only present when the executor is enabled. Field-injected so
     *  subclass constructors stay unchanged. When absent, hunts still complete. */
    @Autowired
    private ObjectProvider<PreySignalEmitter> signalEmitter;

    protected HuntController(String token, PreyRepository preyRepo, ToolFetchCache cache) {
        this.verifier = new BearerTokenVerifier(token);
        this.preyRepo = preyRepo;
        this.cache = cache;
    }

    protected abstract String agentName();
    protected abstract de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> input);
    protected abstract String defaultAnomalyType();
    protected String defaultHorizon() { return "3m"; }
    protected boolean skipBlankSymbol() { return false; }

    /** Key used in the fetch response envelope, e.g. "candidates" or "clusters". */
    protected String fetchOutputKey() { return "candidates"; }

    /** The fetch tool name (matches the *Defaults FETCH constant), used as the cache key. */
    protected abstract String toolName();

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
        String paramsKey = "default";
        if (body != null && body.get("input") instanceof Map<?, ?> in && in.get("lookback_days") != null) {
            paramsKey = String.valueOf(in.get("lookback_days"));
        }
        Map<String, Object> out = cache.get(toolName(), paramsKey,
                () -> {
                    de.visterion.dracul.hunting.DataSourceResult<?> r = hunt(body);
                    Map<String, Object> output = new java.util.HashMap<>();
                    output.put(fetchOutputKey(), r.items());
                    output.put("data_source_health", healthMap(r.health()));
                    return Map.of("output", output);
                },
                HuntController::isHealthyPayload);
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> healthMap(de.visterion.dracul.hunting.DataSourceHealth h) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", h.status());
        m.put("source", h.source());
        m.put("detail", h.detail());          // nullable
        m.put("checked_at", h.checkedAt().toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static boolean isHealthyPayload(Map<String, Object> payload) {
        Object output = payload.get("output");
        if (!(output instanceof Map<?, ?> o)) return true;
        Object health = o.get("data_source_health");
        if (!(health instanceof Map<?, ?> hm)) return true;
        return "healthy".equals(hm.get("status"));
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
        var inserted = preyRepo.insertAll(prey);
        if (inserted.isEmpty()) {
            log.info("{} run {} — all {} prey already persisted (duplicate delivery?)", agentName(), runId, prey.size());
            return ResponseEntity.noContent().build();
        }
        log.info("{} run {} persisted {} prey ({} duplicates skipped)",
                agentName(), runId, inserted.size(), prey.size() - inserted.size());
        // Feed the executor when it is enabled; a disabled executor wires no bean
        // and the hunt still completes normally.
        signalEmitter.ifAvailable(e -> e.emit(inserted));
        return ResponseEntity.noContent().build();
    }
}
