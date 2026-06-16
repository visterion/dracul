package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.edgar.EdgarMergerAdapter;
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
@ConditionalOnProperty(value = "dracul.strigoi.merger.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-merger")
public class StrigoiMergerWebhookController extends HuntController {

    private final EdgarMergerAdapter edgar;
    private final MergerScreener screener;
    private final int defaultLookback;

    public StrigoiMergerWebhookController(
            @Value("${dracul.strigoi.merger.webhook-token}") String token,
            EdgarMergerAdapter edgar,
            MergerScreener screener,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            @Value("${dracul.strigoi.merger.lookback-days:45}") int defaultLookback) {
        super(token, preyRepo, cache);
        this.edgar = edgar;
        this.screener = screener;
        this.defaultLookback = defaultLookback;
    }

    @Override protected String agentName() { return "strigoi-merger"; }
    @Override protected String defaultAnomalyType() { return "MERGER_ARB"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_recent_merger_candidates"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, defaultLookback, 1, 120);
        var to = LocalDate.now();
        return de.visterion.dracul.hunting.DataSourceResult.healthy("edgar",
                screener.screen(edgar.recentDeals(to.minusDays(lookback), to)));
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
