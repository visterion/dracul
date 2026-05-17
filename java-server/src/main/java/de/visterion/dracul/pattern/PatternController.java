package de.visterion.dracul.pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
public class PatternController {

    private final PatternRepository repo;

    public PatternController(PatternRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/patterns")
    public List<Pattern> patterns() {
        return repo.findAllByUser("default");
    }

    public record PatternActionRequest(String action) {}

    @PatchMapping("/api/patterns/{id}")
    public ResponseEntity<Void> action(@PathVariable String id,
                                       @RequestBody PatternActionRequest req) {
        return switch (req.action()) {
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
