package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.edgar.EdgarFormFourAdapter;
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

    private final EdgarFormFourAdapter edgar;
    private final InsiderClusterScreener screener;

    public StrigoiInsiderWebhookController(
            @Value("${dracul.strigoi.insider.webhook-token}") String token,
            EdgarFormFourAdapter edgar,
            InsiderClusterScreener screener,
            PreyRepository preyRepo,
            ToolFetchCache cache) {
        super(token, preyRepo, cache);
        this.edgar = edgar;
        this.screener = screener;
    }

    @Override protected String agentName() { return "strigoi-insider"; }
    @Override protected String defaultAnomalyType() { return "INSIDER_CLUSTER"; }
    @Override protected String fetchOutputKey() { return "clusters"; }
    @Override protected String toolName() { return "fetch_recent_clusters"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, 7, 1, 30);
        var to = LocalDate.now();
        var raw = edgar.recentFilings(to.minusDays(lookback), to);
        return new de.visterion.dracul.hunting.DataSourceResult<>(screener.cluster(raw.items()), raw.health());
    }

    @PostMapping("/tools/fetch-clusters")
    public ResponseEntity<Map<String, Object>> fetchClusters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
