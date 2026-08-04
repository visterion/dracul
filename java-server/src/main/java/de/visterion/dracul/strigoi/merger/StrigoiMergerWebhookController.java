package de.visterion.dracul.strigoi.merger;

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
@ConditionalOnProperty(value = "dracul.strigoi.merger.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-merger")
public class StrigoiMergerWebhookController extends HuntController {

    private final AgoraFilings filings;
    private final MergerScreener screener;
    private final MergerEnrichmentService enrichment;
    private final int defaultLookback;

    public StrigoiMergerWebhookController(
            @Value("${dracul.strigoi.merger.webhook-token}") String token,
            AgoraFilings filings,
            MergerScreener screener,
            MergerEnrichmentService enrichment,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            @Value("${dracul.strigoi.merger.lookback-days:45}") int defaultLookback) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.filings = filings;
        this.screener = screener;
        this.enrichment = enrichment;
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
        var raw = filings.searchMergers(to.minusDays(lookback), to);
        var enriched = enrichment.enrich(screener.screen(raw.items()));
        return new de.visterion.dracul.hunting.DataSourceResult<>(
                enriched.candidates(), mergeHealth(raw.health(), enriched));
    }

    /**
     * ORs the enrichment's own degradations into Agora's fetch health. The health used to come
     * exclusively from {@code searchMergers}, so two Dracul-side losses were invisible: the
     * candidate cap cutting the screened list (always the OLDEST deals — EFTS returns file_date
     * DESC), and per-candidate term sheets that never arrived, which is what left the deal terms
     * unparsed for six DEFM14A proxies on every production run.
     *
     * <p>A cap cut is {@code truncated} ("more exist than you were shown"); missing term sheets are
     * {@code partial} ("what you were shown is incomplete"). An {@code unavailable} status passes
     * through untouched — see {@link DataSourceHealth#degradedWith}.
     */
    static DataSourceHealth mergeHealth(DataSourceHealth agora, EnrichedMergerBatch batch) {
        if (!batch.degraded()) return agora;
        StringBuilder detail = new StringBuilder();
        if (batch.truncated()) {
            detail.append("candidate list capped at dracul.strigoi.merger.max-candidates "
                    + "(newest filings kept, oldest deals dropped)");
        }
        if (batch.filingTextFailures() > 0) {
            if (!detail.isEmpty()) detail.append("; ");
            detail.append("partial: ").append(batch.filingTextFailures())
                    .append(" term sheet(s) could not be fetched, so their deal terms are unparsed");
            if (batch.oversizedFilings() > 0) {
                // Named separately because it is NOT an outage and will not fix itself on a retry.
                detail.append(" (").append(batch.oversizedFilings())
                        .append(" of them exceed Agora's filing-size cap)");
            }
        }
        return DataSourceHealth.degradedWith(agora, detail.toString(),
                batch.filingTextFailures() > 0, batch.truncated());
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
