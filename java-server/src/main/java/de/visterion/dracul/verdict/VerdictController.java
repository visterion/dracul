package de.visterion.dracul.verdict;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerdictController {

    private final VerdictRepository repo;

    public VerdictController(VerdictRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/verdict/{id}")
    public ResponseEntity<VerdictDetail> verdictDetail(@PathVariable String id) {
        return repo.findDetailById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
