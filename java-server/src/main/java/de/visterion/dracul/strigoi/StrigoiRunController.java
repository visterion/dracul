package de.visterion.dracul.strigoi;

import de.visterion.dracul.error.ErrorResponse;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

/** Operator seam: trigger an ad-hoc hunt for a single strigoi via Vistierie. */
@RestController
public class StrigoiRunController {

    public record RunTriggered(String runId) {}

    private final VistierieClient vistierie;

    public StrigoiRunController(VistierieClient vistierie) {
        this.vistierie = vistierie;
    }

    @PostMapping("/api/strigoi/{name}/run")
    public ResponseEntity<?> trigger(@PathVariable String name) {
        boolean known = vistierie.listStrigoi().stream().anyMatch(s -> s.name().equals(name));
        if (!known) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("NOT_FOUND", "unknown strigoi: " + name));
        }
        try {
            var detail = vistierie.triggerRun(name);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunTriggered(detail.id()));
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 409) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.of("AGENT_PAUSED", "agent is paused"));
            }
            if (status == 402 || status == 429) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(ErrorResponse.of("BUDGET_EXCEEDED", "budget exceeded, run rejected"));
            }
            throw e;
        }
    }
}
