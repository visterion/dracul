package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.yahoo.YahooEarningsAdapter;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-echo")
public class StrigoiEchoWebhookController extends HuntController {

    private final YahooEarningsAdapter yahoo;
    private final EchoPeadScreener screener;

    public StrigoiEchoWebhookController(
            @Value("${dracul.strigoi.echo.webhook-token}") String token,
            YahooEarningsAdapter yahoo,
            EchoPeadScreener screener,
            PreyRepository preyRepo,
            ToolFetchCache cache) {
        super(token, preyRepo, cache);
        this.yahoo = yahoo;
        this.screener = screener;
    }

    @Override protected String agentName() { return "strigoi-echo"; }
    @Override protected String defaultAnomalyType() { return "PEAD"; }
    @Override protected String toolName() { return "fetch_recent_pead_candidates"; }

    @Override
    protected List<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, 7, 1, 30);
        var to = LocalDate.now();
        return screener.screen(yahoo.recentEarnings(to.minusDays(lookback), to));
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
