package de.visterion.dracul.benchmark;

import de.visterion.dracul.error.ErrorResponse;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-time backfill of {@code screen_candidate} from Vistierie's recorded fetch-tool outputs
 * (spec 2026-10-13 §2.4). Machine auth: bearer {@code DRACUL_BENCHMARK_IMPORT_TOKEN}, compared in
 * constant time like the hunter webhooks; the path is excluded from the Cloudflare filter, so a
 * Cloudflare login alone never reaches it. A blank token switches the endpoint off — it answers
 * 404, not 401, matching an unmapped path and not revealing that the endpoint merely sits disabled
 * (a malformed-body 400 or wrong-method 405 can still leak path existence; Spring routes and parses
 * before this class ever runs). The whole body is validated before anything is stored; storing is
 * idempotent (UNIQUE (agent, as_of_date, symbol)).
 */
@RestController
@RequestMapping("/api/benchmark/candidates")
public class BenchmarkImportController {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkImportController.class);

    private final boolean enabled;
    private final BearerTokenVerifier verifier;
    private final ScreenCandidateRecorder recorder;

    public BenchmarkImportController(@Value("${dracul.benchmark.import-token:}") String token,
                                     ScreenCandidateRecorder recorder) {
        this.enabled = token != null && !token.isBlank();
        this.verifier = new BearerTokenVerifier(enabled ? token : null);
        this.recorder = recorder;
    }

    private record Group(ScreenItemMapper mapper, Instant fetchedAt, List<JsonNode> items) {}

    @PostMapping("/import")
    public ResponseEntity<Object> importCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {
        if (!enabled) return ResponseEntity.notFound().build();
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        if (body == null || !body.isArray()) {
            return bad("body must be a JSON array of {agent, fetched_at, items}");
        }
        List<Group> groups = new ArrayList<>();
        for (JsonNode g : body) {
            JsonNode agentNode = g.path("agent");
            String agent = agentNode.isString() ? agentNode.asString() : null;
            Optional<ScreenItemMapper> mapper = ScreenItemMappers.forAgent(agent);
            if (mapper.isEmpty()) return bad("unknown agent: " + agent);
            JsonNode fetchedAtNode = g.path("fetched_at");
            if (!fetchedAtNode.isString()) {
                return bad("fetched_at must be an ISO-8601 instant");
            }
            Instant fetchedAt;
            try {
                fetchedAt = Instant.parse(fetchedAtNode.asString());
            } catch (DateTimeParseException | IllegalArgumentException e) {
                return bad("fetched_at must be an ISO-8601 instant");
            }
            JsonNode items = g.path("items");
            if (!items.isArray()) return bad("items must be an array");
            List<JsonNode> list = new ArrayList<>();
            items.forEach(list::add);
            groups.add(new Group(mapper.get(), fetchedAt, list));
        }
        CaptureCounts total = CaptureCounts.ZERO;
        for (Group g : groups) {
            total = total.plus(recorder.store(g.mapper().agent(), g.fetchedAt(), g.mapper(), g.items(),
                    ScreenCandidateRecorder.SOURCE_BACKFILL));
        }
        log.info("benchmark import: groups={} stored={} duplicate={} symbol-less={}",
                groups.size(), total.stored(), total.duplicate(), total.symbolLess());
        return ResponseEntity.ok(Map.of("groups", groups.size(), "stored", total.stored(),
                "duplicate", total.duplicate(), "symbolLess", total.symbolLess()));
    }

    private static ResponseEntity<Object> bad(String message) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", message));
    }
}
