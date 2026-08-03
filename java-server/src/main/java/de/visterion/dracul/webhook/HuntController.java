package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.error.ErrorResponse;
import de.visterion.dracul.executor.PreySignalEmitter;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;

import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Base for the 6 prey-producing hunters. Subclasses declare their own
 *  fetch @PostMapping (the sub-path varies — e.g. insider uses /tools/fetch-clusters)
 *  and delegate to {@link #handleFetch}. The /complete endpoint is uniform and owned here. */
public abstract class HuntController {

    /** Bound to {@code getClass()}, so a subclass logs under its own name. Protected because a
     *  subclass with its own tool endpoint must be able to log a degraded fetch: a degradation
     *  nobody logs is invisible, which is the diagnostic gap this hardening exists to avoid. */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final BearerTokenVerifier verifier;
    private final PreyRepository preyRepo;
    private final PreyMapper preyMapper = new PreyMapper();
    private final ToolFetchCache cache;
    private final HiveMemResearchService memory;
    private final ResearchMemoryLinkRepository memoryLinks;

    /** Optional: only present when the executor is enabled. Field-injected so
     *  subclass constructors stay unchanged. When absent, hunts still complete. */
    @Autowired
    private ObjectProvider<PreySignalEmitter> signalEmitter;

    /** Optional: PatternRepository is always registered in practice, but field-injected
     *  via ObjectProvider for symmetry with signalEmitter and so a missing bean degrades
     *  gracefully (fetch response simply omits active_patterns) rather than failing hunts. */
    @Autowired
    private ObjectProvider<PatternRepository> patternRepo;

    protected HuntController(String token, PreyRepository preyRepo, ToolFetchCache cache,
            HiveMemResearchService memory, ResearchMemoryLinkRepository memoryLinks) {
        this.verifier = new BearerTokenVerifier(token);
        this.preyRepo = preyRepo;
        this.cache = cache;
        this.memory = memory;
        this.memoryLinks = memoryLinks;
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

    /** Token-Prüfung für Subklassen, die einen ZWEITEN Tool-Endpoint anbieten
     *  (z.B. strigoi-echos fetch-news). {@code verifier} bleibt privat. */
    protected final boolean authorized(String auth) {
        return verifier.verify(auth);
    }

    /** Marks a failure envelope produced by this controller's own guard, as opposed to a
     *  legitimate source-driven unavailable (e.g. AgoraEarnings on an Agora outage, which
     *  writes the same status). Rollout verification counts responses carrying this prefix;
     *  without it the two are indistinguishable. See spec §6.1. */
    protected static final String GUARD_MARKER = "tool-guard: ";

    private static final int MAX_DETAIL_CHARS = 500;

    /** Envelope for a tool endpoint's FAILURE responses. The success paths still build their
     *  own envelope ({@link #handleFetch} and StrigoiEchoWebhookController#fetchNews) because
     *  the cache stores the already-wrapped payload; unifying them is cleanup for a later
     *  change. */
    protected static ResponseEntity<Map<String, Object>> ok(Map<String, Object> output) {
        return ResponseEntity.ok(Map.of("output", output));
    }

    /** Failure body: the tool's own result fields, empty, plus an unavailable health block.
     *  {@code emptyResult} supplies the result key so this works for insider's "clusters" and
     *  echo's "news" alike — the key is never hard-coded. {@code detail} is truncated because
     *  it rides into the agent's context window. */
    protected static Map<String, Object> unavailable(Map<String, Object> emptyResult,
                                                     String source, String detail) {
        Map<String, Object> out = new HashMap<>(emptyResult);
        Map<String, Object> health = new HashMap<>();
        health.put("status", "unavailable");
        health.put("source", source);
        health.put("detail", detail == null ? null
                : detail.substring(0, Math.min(detail.length(), MAX_DETAIL_CHARS)));
        health.put("checked_at", Instant.now().toString());
        out.put("data_source_health", health);
        return out;
    }

    /** Result key of the FAILING method. Not {@link #fetchOutputKey()} alone: that is one value
     *  per controller, but echo has TWO tool endpoints with different keys ("candidates" and
     *  "news"). Default is the candidate key; echo overrides it for fetchNews. */
    protected String outputKeyFor(HandlerMethod method) { return fetchOutputKey(); }

    /** Subclasses call this from their own @PostMapping("/tools/...") method. */
    protected ResponseEntity<Map<String, Object>> handleFetch(String auth, Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        // Normalised once, here — not scattered into the six controllers' hunt() bodies. A
        // forgotten site would be invisible: the catch below would turn the resulting NPE into
        // a green 200, which is exactly the regression this substitution must prevent.
        final Map<String, Object> effectiveBody = body == null ? Map.of() : body;
        String paramsKey = "default";
        if (effectiveBody.get("input") instanceof Map<?, ?> in && in.get("lookback_days") != null) {
            paramsKey = String.valueOf(in.get("lookback_days"));
        }
        try {
            Map<String, Object> out = cache.get(toolName(), paramsKey,
                    () -> {
                        de.visterion.dracul.hunting.DataSourceResult<?> r = hunt(effectiveBody);
                        Map<String, Object> output = new java.util.HashMap<>();
                        output.put(fetchOutputKey(), r.items());
                        output.put("data_source_health", healthMap(r.health()));
                        // Learning-loop feedback: user-accepted patterns relevant to this hunter.
                        // Rides the ToolFetchCache above, so a pattern approved/rejected after
                        // this tool was last invoked only becomes visible once the cache entry
                        // expires (see ToolFetchCache TTL) — acceptable for v1.
                        patternRepo.ifAvailable(repo ->
                                output.put("active_patterns", repo.findAcceptedByStrigoi(agentName())));
                        return Map.of("output", output);
                    },
                    HuntController::isHealthyPayload);
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            // A 4xx from a tool endpoint makes Vistierie terminate the whole agent run and
            // discard every prey it already produced — so a failing hunt() degrades to an
            // "unavailable" 200 instead. The catch sits outside cache.get so a throw stores
            // nothing. RuntimeException, not Throwable: an Error must not be swallowed.
            log.warn("{} tool {} failed — answering unavailable instead of 4xx: {}",
                    agentName(), toolName(), e.toString(), e);
            return ok(unavailable(Map.of(fetchOutputKey(), List.of()),
                    agentName(), GUARD_MARKER + e));
        }
    }

    private static Map<String, Object> healthMap(de.visterion.dracul.hunting.DataSourceHealth h) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", h.status());
        m.put("source", h.source());
        m.put("detail", h.detail());          // nullable
        m.put("checked_at", h.checkedAt().toString());
        // Only when set: a clean payload must look exactly as it did before this change, so no
        // agent prompt has to learn a new field it will see on every healthy day.
        if (h.partial()) m.put("partial", true);
        if (h.truncated()) m.put("truncated", true);
        return m;
    }

    /** Cache admission predicate: only a payload provably healthy may be cached. Both early
     *  returns are {@code false} — a missing {@code output} or {@code data_source_health}
     *  block is NOT treated as healthy. Unreachable today: both current callers
     *  ({@link #handleFetch} and StrigoiEchoWebhookController#fetchNews) always set the
     *  health block, and the §3.2 failure envelope is built outside {@code cache.get}, so it
     *  never reaches this predicate either. Kept as a guard for a later change that routes
     *  failure envelopes through the cache, where a health-less payload would otherwise be
     *  cached for the full TTL. */
    @SuppressWarnings("unchecked")
    private static boolean isHealthyPayload(Map<String, Object> payload) {
        Object output = payload.get("output");
        if (!(output instanceof Map<?, ?> o)) return false;
        Object health = o.get("data_source_health");
        if (!(health instanceof Map<?, ?> hm)) return false;
        if (!"healthy".equals(hm.get("status"))) return false;
        // A known-incomplete answer must not be held for the full TTL — that would freeze the
        // blind spot. Agora keeps its own partial answers for 600s for exactly this reason.
        return !Boolean.TRUE.equals(hm.get("partial")) && !Boolean.TRUE.equals(hm.get("truncated"));
    }

    /** Health-Map für Subklassen mit eigenem Tool-Endpoint. */
    protected static Map<String, Object> healthOf(de.visterion.dracul.hunting.DataSourceHealth h) {
        return healthMap(h);
    }

    /** Cache-Zugriff für Subklassen mit eigenem Tool-Endpoint. */
    protected ToolFetchCache cache() { return cache; }

    /** Cache-Prädikat für Subklassen: unavailable-Payloads werden nicht festgehalten. */
    protected static boolean healthyPayload(Map<String, Object> payload) {
        return isHealthyPayload(payload);
    }

    /** A body that fails to bind throws during argument resolution, before any handler method
     *  runs — so neither the 401 check nor the guard in {@link #handleFetch} can see it. This
     *  handler is the only place that can, and being declared here it is inherited by all six
     *  hunters and takes precedence over {@code GlobalExceptionHandler}.
     *
     *  <p>It must discriminate three things a naive version gets wrong:
     *  (1) {@link #complete} lives on this same class and is deliberately NOT part of the rule
     *      — without the exemption a truncated completion payload would answer 200 from a
     *      method typed {@code ResponseEntity<Void>}, and Vistierie would record the completion
     *      as accepted and never retry it;
     *  (2) the token check never ran, so it is repeated here — otherwise an unauthenticated
     *      call with broken JSON gets a clean 200;
     *  (3) the result key follows the failing METHOD, not the controller (echo has two).
     *
     *  <p>It never throws: a throwing {@code @ExceptionHandler} makes the resolver return null,
     *  {@code GlobalExceptionHandler} is NOT consulted again, and the response would come from
     *  {@code DefaultHandlerExceptionResolver} with a different body than today. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadableBody(HttpMessageNotReadableException e,
                                                 HandlerMethod method,
                                                 HttpServletRequest request) {
        if ("complete".equals(method.getMethod().getName())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("VALIDATION_ERROR", "Malformed JSON"));
        }
        if (!authorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return ResponseEntity.status(401).build();
        }
        log.warn("{} received an unreadable tool body on {}: {}",
                agentName(), method.getMethod().getName(), e.toString());
        return ResponseEntity.ok(Map.of("output",
                unavailable(Map.of(outputKeyFor(method), List.of()),
                        agentName(), GUARD_MARKER + "unreadable request body")));
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
        var inserted = preyRepo.insertAll(prey, runId);
        if (inserted.isEmpty()) {
            log.info("{} run {} — all {} prey already persisted (duplicate delivery?)", agentName(), runId, prey.size());
            return ResponseEntity.noContent().build();
        }
        log.info("{} run {} persisted {} prey ({} duplicates skipped)",
                agentName(), runId, inserted.size(), prey.size() - inserted.size());
        // Feed the executor when it is enabled; a disabled executor wires no bean
        // and the hunt still completes normally.
        signalEmitter.ifAvailable(e -> e.emit(inserted));
        // Post-persistence hook (no-op by default; overridden by strigoi-spin for
        // candidate→prey promotion). Invoked exactly once per completion that persisted
        // at least one NEW prey, with the non-empty {@code inserted} list — never on the
        // "no prey" or "all-duplicates" early returns above. This mirrors signalEmitter:
        // only newly-inserted prey trigger downstream effects, so a retried/duplicate
        // delivery (inserted empty) skips the hook and can never re-fire promotion.
        afterPersist(inserted, body);
        // Write-back hook (T1.6 Task 9): one thesis cell + one research_memory_link row per
        // newly-inserted prey, AFTER afterPersist so a spin/index promotion side effect is
        // durably recorded before any memory write is attempted. Best-effort: writeThesisMemory
        // is itself guarded/never-throwing (HiveMemResearchService), and the outer try/catch here
        // is defense-in-depth so a bug in the link-insert path (Dracul's own DB) can't 500 this
        // completion either — a memory-write failure must never fail a hunt.
        for (Prey p : inserted) {
            try {
                memory.writeThesisMemory("prey", p.symbol(), p.anomalyType(), p.thesis(),
                            p.signals(), p.risks(), p.killCriteria(), p.horizon(), p.discoveredBy(),
                            p.confidence(), p.id())
                        .ifPresent(cellId -> memoryLinks.insert("prey", p.id(), p.symbol(), cellId));
            } catch (RuntimeException e) {
                log.warn("{} run {} — memory write for prey {} failed unexpectedly: {}",
                        agentName(), runId, p.id(), e.getMessage());
            }
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Extension point called at the end of a successful {@link #complete} that persisted at least
     * one new prey, after the prey insertion and executor emission. The {@code inserted} list holds
     * only the prey actually written this delivery (duplicates already filtered by the natural-key
     * ON CONFLICT), so an override sees each emitted prey exactly once across retried deliveries.
     *
     * <p>Default is a deliberate no-op: four of the six hunters have no post-persistence work and
     * inherit this unchanged. {@code strigoi-spin} and {@code strigoi-index} override it to mark the
     * originating {@code spin_candidate} / {@code index_event} row promoted (idempotency stamp; see
     * StrigoiSpinWebhookController / StrigoiIndexWebhookController).
     * Overrides must be fail-soft — this runs after the prey are durably persisted, so a throw here
     * must not fail the completion.
     */
    protected void afterPersist(List<Prey> inserted, JsonNode body) {
        // no-op
    }
}
