package de.visterion.dracul.verdict;

import de.visterion.dracul.settings.AppSettingsRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
public class VerdictController {

    private final VerdictRepository repo;
    private final AppSettingsRepository settings;
    private final VerdictCurrencyMapper mapper;

    public VerdictController(VerdictRepository repo, AppSettingsRepository settings,
                             VerdictCurrencyMapper mapper) {
        this.repo = repo;
        this.settings = settings;
        this.mapper = mapper;
    }

    @GetMapping("/api/verdict/{id}")
    public ResponseEntity<VerdictDetail> verdictDetail(@PathVariable String id) {
        return repo.findDetailById(id)
                .map(d -> mapper.toDisplay(d, settings.getDisplayCurrency()))
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
