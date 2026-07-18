package de.visterion.dracul.pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class PatternController {

    private static final String USER = "default";

    private final PatternRepository repo;
    private final ObjectMapper mapper;

    public PatternController(PatternRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** API view of a pattern (T3.3 D6): every stored field plus the parsed {@code gate}
     *  JSON (null = advisory-only) and the read-time {@code blockedCount} — distinct
     *  signals rejected with reason PATTERN_GATE attributed to this pattern. Attribution
     *  bias is documented: with overlapping gates all blocks attribute to the first match. */
    public record PatternView(String id, String appliesToStrigoi, String statement,
            String status, int evidenceCount, String proposedAt, Integer supportedCount,
            Double avgUpliftPercent, String name, JsonNode gate, long blockedCount) {}

    @GetMapping("/api/patterns")
    public List<PatternView> patterns() {
        Map<String, Long> blocked = repo.blockedCounts();
        List<PatternView> views = new ArrayList<>();
        for (Pattern p : repo.findAllByUser(USER)) {
            views.add(toView(p, blocked.getOrDefault(p.id(), 0L)));
        }
        return views;
    }

    @GetMapping("/api/patterns/{id}/cases")
    public ResponseEntity<List<PatternCase>> cases(@PathVariable String id) {
        if (repo.findById(id, USER).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repo.findCases(id, USER));
    }

    @PatchMapping("/api/patterns/{id}")
    public ResponseEntity<?> action(@PathVariable String id, @RequestBody JsonNode req) {
        String action = req.path("action").asText(null);
        if (action == null) return ResponseEntity.badRequest().build();
        return switch (action) {
            case "approve" -> approve(id);
            case "reject", "deactivate" -> {
                repo.updateStatus(id, USER, "REJECTED");
                yield ResponseEntity.noContent().build();
            }
            case "defer" -> ResponseEntity.noContent().build();
            case "update_gate" -> updateGate(id, req);
            default -> ResponseEntity.badRequest().build();
        };
    }

    /** Approve = enforce immediately when a gate is stored (T3.3): allowed only from
     *  PENDING — a REJECTED pattern with a stored gate must never be silently re-armed. */
    private ResponseEntity<?> approve(String id) {
        Optional<Pattern> pattern = repo.findById(id, USER);
        if (pattern.isEmpty()) return ResponseEntity.notFound().build();
        if (!"PENDING".equals(pattern.get().status())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "approve requires status PENDING, was " + pattern.get().status()));
        }
        repo.updateStatus(id, USER, "ACTIVE");
        repo.setName(id, USER, generateSlug(id));
        return ResponseEntity.noContent().build();
    }

    /** T3.3 D6: {@code gate} missing → 400 (guards against accidentally wiping an armed
     *  gate); {@code gate: null} → explicit clear; object → validate. Allowed for
     *  PENDING/ACTIVE (an ACTIVE edit takes effect on the next evaluation); rejected for
     *  REJECTED patterns. Delegates to the transactional replaceGate, which also
     *  invalidates auto-evidence of the old predicate. */
    private ResponseEntity<?> updateGate(String id, JsonNode req) {
        Optional<Pattern> pattern = repo.findById(id, USER);
        if (pattern.isEmpty()) return ResponseEntity.notFound().build();
        if ("REJECTED".equals(pattern.get().status())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "update_gate not allowed on REJECTED patterns"));
        }
        if (!req.has("gate")) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "gate field required (use null to clear)"));
        }
        JsonNode gate = req.get("gate");
        if (gate.isNull()) {
            repo.replaceGate(id, USER, null);
            return ResponseEntity.noContent().build();
        }
        GateValidator.Result result = GateValidator.validate(gate);
        if (!result.valid()) {
            return ResponseEntity.badRequest().body(Map.of("errors", result.errors()));
        }
        repo.replaceGate(id, USER, gate.toString());
        return ResponseEntity.noContent().build();
    }

    private PatternView toView(Pattern p, long blockedCount) {
        JsonNode gate = null;
        if (p.gateJson() != null) {
            try {
                gate = mapper.readTree(p.gateJson());
            } catch (RuntimeException e) {
                gate = null; // defense in depth: an unreadable stored gate renders as null
            }
        }
        return new PatternView(p.id(), p.appliesToStrigoi(), p.statement(), p.status(),
                p.evidenceCount(), p.proposedAt(), p.supportedCount(), p.avgUpliftPercent(),
                p.name(), gate, blockedCount);
    }

    private String generateSlug(String id) {
        return repo.findById(id, USER)
                .map(p -> {
                    var words = p.statement()
                            .toLowerCase()
                            .replaceAll("[^a-z0-9\\s]", "")
                            .trim()
                            .split("\\s+");
                    return String.join("-", Arrays.copyOf(words, Math.min(words.length, 5)));
                })
                .orElse(id);
    }
}
