package de.visterion.dracul.pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
