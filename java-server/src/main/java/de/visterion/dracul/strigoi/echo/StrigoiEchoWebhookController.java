package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-echo")
public class StrigoiEchoWebhookController extends HuntController {

    private final AgoraEarnings earnings;
    private final EchoPeadScreener screener;
    private final EchoEnrichmentService enrichment;
    private final AgoraCompanyData companyData;

    public StrigoiEchoWebhookController(
            @Value("${dracul.strigoi.echo.webhook-token}") String token,
            AgoraEarnings earnings,
            EchoPeadScreener screener,
            EchoEnrichmentService enrichment,
            AgoraCompanyData companyData,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.earnings = earnings;
        this.screener = screener;
        this.enrichment = enrichment;
        this.companyData = companyData;
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

    /** Detail-Tool: die vollen News EINES Symbols inkl. {@code summary}. Der Kandidaten-Payload
     *  trägt nur einen summary-losen Index (Spec 2026-07-27, §3.2); wer den Volltext braucht,
     *  zieht ihn hier nach. Läuft über denselben ToolFetchCache wie fetch-candidates, mit dem
     *  Symbol als paramsKey — wiederholte Calls fürs selbe Symbol kosten keinen zweiten
     *  Agora-Fetch. Ein Agora-Ausfall degradiert fail-soft zu einer leeren Liste mit
     *  {@code data_source_health.status = "unavailable"}, damit der Cache ihn nicht festhält. */
    @PostMapping("/tools/fetch-news")
    public ResponseEntity<Map<String, Object>> fetchNews(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();

        String symbol = null;
        String sinceRaw = null;
        if (body != null && body.get("input") instanceof Map<?, ?> in) {
            if (in.get("symbol") != null) symbol = String.valueOf(in.get("symbol")).trim();
            if (in.get("since") != null) sinceRaw = String.valueOf(in.get("since")).trim();
        }
        if (symbol == null || symbol.isBlank()) return ResponseEntity.badRequest().build();

        LocalDate since = LocalDate.now().minusDays(30);
        if (sinceRaw != null && !sinceRaw.isBlank()) {
            try { since = LocalDate.parse(sinceRaw); }
            catch (Exception e) { /* unparsbares since → 30-Tage-Default, Obermenge */ }
        }
        final String sym = symbol;
        final LocalDate from = since;

        Map<String, Object> out = cache().get("fetch_candidate_news", sym + ":" + from,
                () -> {
                    Map<String, Object> output = new HashMap<>();
                    try {
                        output.put("news", companyData.news(sym, from, LocalDate.now()));
                        output.put("data_source_health", healthOf(DataSourceHealth.healthy("agora")));
                    } catch (Exception e) {
                        output.put("news", java.util.List.of());
                        output.put("data_source_health",
                                healthOf(DataSourceHealth.unavailable("agora", e.getMessage())));
                    }
                    return Map.of("output", output);
                },
                StrigoiEchoWebhookController::healthyPayload);
        return ResponseEntity.ok(out);
    }
}
