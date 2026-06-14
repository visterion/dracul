package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.wikipedia.WikipediaSp500Adapter;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.index.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-index")
public class StrigoiIndexWebhookController extends HuntController {

    private final WikipediaSp500Adapter wikipedia;
    private final IndexScreener screener;
    private final int defaultLookback;

    public StrigoiIndexWebhookController(
            @Value("${dracul.strigoi.index.webhook-token}") String token,
            WikipediaSp500Adapter wikipedia,
            IndexScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.index.lookback-days:30}") int defaultLookback) {
        super(token, preyRepo);
        this.wikipedia = wikipedia;
        this.screener = screener;
        this.defaultLookback = defaultLookback;
    }

    @Override protected String agentName() { return "strigoi-index"; }
    @Override protected String defaultAnomalyType() { return "INDEX_INCLUSION"; }
    @Override protected String defaultHorizon() { return "1m"; }
    @Override protected boolean skipBlankSymbol() { return true; }

    @Override
    protected List<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, defaultLookback, 1, 90);
        return screener.screen(wikipedia.recentConstituents(), lookback);
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
