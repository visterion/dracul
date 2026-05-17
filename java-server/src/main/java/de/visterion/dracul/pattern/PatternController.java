package de.visterion.dracul.pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
public class PatternController {

    private final PatternRepository repo;
    private final ObjectMapper objectMapper;

    public PatternController(PatternRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/patterns")
    public List<Pattern> patterns() {
        return repo.findAllByUser("default");
    }

    @PatchMapping("/api/patterns/{id}")
    public ResponseEntity<Void> action(@PathVariable String id,
                                       @RequestBody String body) {
        String action;
        try {
            var tree = objectMapper.readTree(body);
            // If the client sent a JSON-encoded string (double-encoded), unwrap it first
            var actionNode = tree.isTextual()
                    ? objectMapper.readTree(tree.asText()).path("action")
                    : tree.path("action");
            action = actionNode.asText();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        return switch (action) {
            case "approve" -> {
                repo.updateStatus(id, "default", "ACTIVE");
                repo.setName(id, "default", generateSlug(id));
                yield ResponseEntity.noContent().build();
            }
            case "reject", "deactivate" -> {
                repo.updateStatus(id, "default", "REJECTED");
                yield ResponseEntity.noContent().build();
            }
            case "defer" -> ResponseEntity.noContent().build();
            default -> ResponseEntity.badRequest().build();
        };
    }

    private String generateSlug(String id) {
        return repo.findById(id, "default")
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
