package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.insider.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-insider")
public class StrigoiInsiderWebhookController extends HuntController {

    private final AgoraFilings filings;
    private final InsiderClusterScreener screener;
    private final InsiderEnrichmentService enrichment;

    public StrigoiInsiderWebhookController(
            @Value("${dracul.strigoi.insider.webhook-token}") String token,
            AgoraFilings filings,
            InsiderClusterScreener screener,
            InsiderEnrichmentService enrichment,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.filings = filings;
        this.screener = screener;
        this.enrichment = enrichment;
    }

    @Override protected String agentName() { return "strigoi-insider"; }
    @Override protected String defaultAnomalyType() { return "INSIDER_CLUSTER"; }
    @Override protected String fetchOutputKey() { return "clusters"; }
    @Override protected String toolName() { return "fetch_recent_clusters"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, 7, 1, 30);
        var to = LocalDate.now();
        var raw = filings.recentForm4(to.minusDays(lookback), to);
        var enriched = enrichment.enrich(screener.cluster(raw.items()));
        return new de.visterion.dracul.hunting.DataSourceResult<>(
                enriched.clusters(), mergeHealth(raw.health(), enriched));
    }

    /**
     * ORs the enrichment's own degradations into Agora's fetch health, exactly as the merger
     * hunter does. Before this, the health came exclusively from {@code recentForm4}, so two
     * Dracul-side losses were invisible: the 25-cluster cap, and clusters that came back without
     * some of their enrichment. The 2026-08-06 run reported {@code partial=false truncated=false
     * status=healthy} while enrichment had in fact been switched off for the whole batch.
     *
     * <p>A cap cut is {@code truncated} ("more exist than you were shown"); missing enrichment is
     * {@code partial} ("what you were shown is incomplete"). An {@code unavailable} status passes
     * through untouched — see {@link DataSourceHealth#degradedWith}.
     */
    static DataSourceHealth mergeHealth(DataSourceHealth agora, EnrichedInsiderBatch batch) {
        if (!batch.degraded()) return agora;
        StringBuilder detail = new StringBuilder();
        if (batch.truncated()) {
            detail.append("cluster list capped at 25 (largest by totalDollarValue kept)");
        }
        if (batch.degradedClusters() > 0) {
            if (!detail.isEmpty()) detail.append("; ");
            detail.append("partial: ").append(batch.degradedClusters())
                    .append(" cluster(s) lost at least one enrichment source");
        }
        return DataSourceHealth.degradedWith(agora, detail.toString(),
                batch.degradedClusters() > 0, batch.truncated());
    }

    @PostMapping("/tools/fetch-clusters")
    public ResponseEntity<Map<String, Object>> fetchClusters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
