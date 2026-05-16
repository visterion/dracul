package de.visterion.dracul.strigoi;

import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StrigoiController {

    private final VistierieClient vistierieClient;
    private final PreyRepository preyRepo;

    public StrigoiController(VistierieClient vistierieClient, PreyRepository preyRepo) {
        this.vistierieClient = vistierieClient;
        this.preyRepo = preyRepo;
    }

    @GetMapping("/api/strigoi/{name}")
    public ResponseEntity<StrigoiDetail> strigoiDetail(@PathVariable String name) {
        return vistierieClient.getStrigoiDetail(name)
                .map(detail -> {
                    var prey = preyRepo.findByDiscoveredBy(name, "default");
                    return new StrigoiDetail(
                            detail.name(), detail.anomalyType(), detail.description(), detail.reference(),
                            detail.state(), detail.lastRunAt(), detail.nextRunAt(),
                            detail.huntsThisMonth(), detail.scheduledHuntsThisMonth(), detail.avgPreyPerHunt(),
                            detail.hitRate90d(), detail.hitRateNumerator(), detail.hitRateDenominator(),
                            detail.recentRuns(), prey,
                            detail.configuration(), detail.weeklyPerformance()
                    );
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
