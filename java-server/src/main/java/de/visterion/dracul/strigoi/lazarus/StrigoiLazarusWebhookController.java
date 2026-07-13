package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-lazarus")
public class StrigoiLazarusWebhookController extends HuntController {

    private static final String USER = "default";

    private final WatchlistRepository watchlist;
    private final AgoraCompanyData companyData;
    private final LazarusScreener screener;
    private final LazarusEnrichmentService enrichment;
    private final HeldPositionService heldPositionService;
    private final String connection;
    private final double maxAboveLow;
    private final double maxDebtEquity;
    private final double maxPriceToBook;
    private final double maxPFcf;

    public StrigoiLazarusWebhookController(
            @Value("${dracul.strigoi.lazarus.webhook-token}") String token,
            WatchlistRepository watchlist,
            AgoraCompanyData companyData,
            LazarusScreener screener,
            LazarusEnrichmentService enrichment,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HeldPositionService heldPositionService,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.strigoi.lazarus.max-above-low:0.10}") double maxAboveLow,
            @Value("${dracul.strigoi.lazarus.max-debt-equity:3.0}") double maxDebtEquity,
            @Value("${dracul.strigoi.lazarus.max-price-to-book:2.0}") double maxPriceToBook,
            @Value("${dracul.strigoi.lazarus.max-p-fcf:20}") double maxPFcf) {
        super(token, preyRepo, cache);
        this.watchlist = watchlist;
        this.companyData = companyData;
        this.screener = screener;
        this.enrichment = enrichment;
        this.heldPositionService = heldPositionService;
        this.connection = connection;
        this.maxAboveLow = maxAboveLow;
        this.maxDebtEquity = maxDebtEquity;
        this.maxPriceToBook = maxPriceToBook;
        this.maxPFcf = maxPFcf;
    }

    @Override protected String agentName() { return "strigoi-lazarus"; }
    @Override protected String defaultAnomalyType() { return "QUALITY_52W_LOW"; }
    @Override protected String defaultHorizon() { return "12m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_quality_at_low_candidates"; }

    @Override
    protected de.visterion.dracul.hunting.DataSourceResult<?> hunt(Map<String, Object> body) {
        // Dedup against the live depot: a watchlist name already held is not a "new" quality-at-low
        // candidate — surfacing it again would just recommend buying what's already owned. Symbols
        // are the join key (the depot has no watchlist_item_id concept); a depot-down fetch yields an
        // empty set (HeldPositionService is fail-soft), so dedup excludes nothing rather than erroring.
        Set<String> heldSymbols = heldPositionService.openPositions(connection).stream()
                .map(HeldPosition::symbol)
                .collect(Collectors.toSet());
        List<WatchlistItem> items = watchlist.findAllByUser(USER).stream()
                .filter(item -> !heldSymbols.contains(item.ticker()))
                .toList();

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
        var screened = screener.screen(raws, maxAboveLow, maxDebtEquity, maxPriceToBook, maxPFcf);
        var enriched = enrichment.enrich(screened);
        return de.visterion.dracul.hunting.DataSourceResult.healthy("agora", enriched);
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
