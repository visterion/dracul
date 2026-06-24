package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceResult;
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
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-echo")
public class StrigoiEchoWebhookController extends HuntController {

    private final EarningsSourceRouter earnings;
    private final EchoPeadScreener screener;
    private final EchoEnrichmentService enrichment;

    public StrigoiEchoWebhookController(
            @Value("${dracul.strigoi.echo.webhook-token}") String token,
            EarningsSourceRouter earnings,
            EchoPeadScreener screener,
            EchoEnrichmentService enrichment,
            PreyRepository preyRepo,
            ToolFetchCache cache) {
        super(token, preyRepo, cache);
        this.earnings = earnings;
        this.screener = screener;
        this.enrichment = enrichment;
    }

    @Override protected String agentName() { return "strigoi-echo"; }
    @Override protected String defaultAnomalyType() { return "PEAD"; }
    @Override protected String toolName() { return "fetch_recent_pead_candidates"; }

    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, 7, 1, 30);
        var to = LocalDate.now();
        var raw = earnings.recent(to.minusDays(lookback), to);
        var enriched = enrichment.enrich(screener.screen(raw.items()));
        return new DataSourceResult<>(enriched, raw.health());
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
