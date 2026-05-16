package de.visterion.dracul.status;

import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    private final VistierieClient vistierieClient;
    private final VerdictRepository verdictRepo;

    public StatusController(VistierieClient vistierieClient, VerdictRepository verdictRepo) {
        this.vistierieClient = vistierieClient;
        this.verdictRepo = verdictRepo;
    }

    @GetMapping("/api/status")
    public SystemStatus status() {
        var strigoi = vistierieClient.listStrigoi();
        var cost    = vistierieClient.getTodayCostUsd();
        var lastVerdict = verdictRepo.findLatestCreatedAt("default");
        return new SystemStatus(strigoi, lastVerdict.orElse(null), cost, false);
    }
}
