package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.prey.PreyRepository;
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
            ToolFetchCache cache) {
        super(token, preyRepo, cache);
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
        return new de.visterion.dracul.hunting.DataSourceResult<>(enriched, raw.health());
    }

    @PostMapping("/tools/fetch-clusters")
    public ResponseEntity<Map<String, Object>> fetchClusters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
