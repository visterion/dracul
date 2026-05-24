package de.visterion.dracul.verdict;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
public class VerdictController {

    private final VerdictRepository repo;

    public VerdictController(VerdictRepository repo) { this.repo = repo; }

    @GetMapping("/api/verdict/{id}")
    public ResponseEntity<VerdictDetail> verdictDetail(@PathVariable String id) {
        return repo.findDetailById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/verdict/{id}/decision")
    public DecisionResponse setDecision(@PathVariable String id,
                                         @Valid @RequestBody DecisionRequest body) {
        String decidedAt = repo.updateDecision(id, body.decision())
                .orElseThrow(() -> new NoSuchElementException("verdict " + id));
        return new DecisionResponse(id, body.decision(), decidedAt);
    }
}
