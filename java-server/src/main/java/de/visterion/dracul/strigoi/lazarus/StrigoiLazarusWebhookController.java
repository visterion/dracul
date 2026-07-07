package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-lazarus")
public class StrigoiLazarusWebhookController extends HuntController {

    private static final String USER = "default";

    private final WatchlistRepository watchlist;
    private final AgoraCompanyData companyData;
    private final LazarusScreener screener;
    private final double maxAboveLow;
    private final double maxDebtEquity;

    public StrigoiLazarusWebhookController(
            @Value("${dracul.strigoi.lazarus.webhook-token}") String token,
            WatchlistRepository watchlist,
            AgoraCompanyData companyData,
            LazarusScreener screener,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            @Value("${dracul.strigoi.lazarus.max-above-low:0.10}") double maxAboveLow,
            @Value("${dracul.strigoi.lazarus.max-debt-equity:3.0}") double maxDebtEquity) {
        super(token, preyRepo, cache);
        this.watchlist = watchlist;
        this.companyData = companyData;
        this.screener = screener;
        this.maxAboveLow = maxAboveLow;
        this.maxDebtEquity = maxDebtEquity;
    }

    @Override protected String agentName() { return "strigoi-lazarus"; }
    @Override protected String defaultAnomalyType() { return "QUALITY_52W_LOW"; }
    @Override protected String defaultHorizon() { return "12m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_quality_at_low_candidates"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        var items = watchlist.findAllByUser(USER);

        // Single upfront reachability check (mirrors the old configured()-key guard: one check per
        // hunt, not per symbol). fundamentals() alone can't tell "Agora is down" apart from "no data
        // for this symbol" — it collapses both to null — so a total outage would otherwise report
        // healthy with all-null financials.
        if (!items.isEmpty()) {
            var probe = companyData.fundamentalsResult(items.get(0).ticker());
            if (!probe.health().isHealthy()) {
                return de.visterion.dracul.hunting.DataSourceResult.unavailable(
                        "agora", probe.health().detail());
            }
        }

        var raws = new ArrayList<LazarusRaw>();
        for (WatchlistItem item : items) {
            raws.add(new LazarusRaw(item.ticker(), item.companyName(), item.currentPrice(),
                    BasicFinancialsExtractor.extract(companyData.fundamentals(item.ticker()))));
        }
        // TODO(slice-2b Task 3): wire real cheapness caps
        return de.visterion.dracul.hunting.DataSourceResult.healthy("agora",
                screener.screen(raws, maxAboveLow, maxDebtEquity, Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
