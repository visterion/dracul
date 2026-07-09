package de.visterion.dracul.executor;

import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.vistierie.VistierieRunDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator seam: trigger an ad-hoc executor run against Vistierie. */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor")
public class ExecutorRunController {

    private final VistierieClient vistierie;

    public ExecutorRunController(VistierieClient vistierie) {
        this.vistierie = vistierie;
    }

    @PostMapping("/run")
    public ResponseEntity<VistierieRunDetail> run() {
        var detail = vistierie.triggerRun(ExecutorDefaults.NAME);
        return ResponseEntity.ok(detail);
    }
}
