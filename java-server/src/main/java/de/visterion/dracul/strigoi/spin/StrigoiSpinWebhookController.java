package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.edgar.EdgarSpinoffAdapter;
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
@ConditionalOnProperty(value = "dracul.strigoi.spin.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-spin")
public class StrigoiSpinWebhookController extends HuntController {

    private final EdgarSpinoffAdapter edgar;
    private final SpinoffScreener screener;
    private final int defaultLookback;

    public StrigoiSpinWebhookController(
            @Value("${dracul.strigoi.spin.webhook-token}") String token,
            EdgarSpinoffAdapter edgar,
            SpinoffScreener screener,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            @Value("${dracul.strigoi.spin.lookback-days:60}") int defaultLookback) {
        super(token, preyRepo, cache);
        this.edgar = edgar;
        this.screener = screener;
        this.defaultLookback = defaultLookback;
    }

    @Override protected String agentName() { return "strigoi-spin"; }
    @Override protected String defaultAnomalyType() { return "SPINOFF"; }
    @Override protected String defaultHorizon() { return "6m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_recent_spinoff_candidates"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, defaultLookback, 1, 90);
        var to = LocalDate.now();
        return de.visterion.dracul.hunting.DataSourceResult.healthy("edgar",
                screener.screen(edgar.recentSpinoffs(to.minusDays(lookback), to)));
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
