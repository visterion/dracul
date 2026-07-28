package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
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
     *  Symbol als paramsKey — SOBALD {@code fetch_candidate_news} als cacheable in den
     *  AgentToolCatalog eingetragen ist (Task 3), kosten wiederholte Calls fürs selbe Symbol
     *  keinen zweiten Agora-Fetch mehr; bis dahin ist der Tool-Name im Catalog nicht registriert,
     *  {@link de.visterion.dracul.agent.ToolFetchCache} liefert also TTL 0 und jeder Call
     *  fetcht neu. Health kommt aus {@link AgoraCompanyData#newsResult}, NICHT aus einem
     *  try/catch um {@link AgoraCompanyData#news} — {@code news()} verschluckt einen
     *  Agora-Ausfall selbst zu einer leeren Liste und würde ihn nie sichtbar machen. Ein
     *  {@code unavailable}-Ergebnis wird über {@link #healthyPayload} nicht gecacht, damit der
     *  nächste Call retried. */
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
                    var result = companyData.newsResult(sym, from, LocalDate.now());
                    Map<String, Object> output = new HashMap<>();
                    output.put("news", result.items());
                    output.put("data_source_health", healthOf(result.health()));
                    return Map.of("output", output);
                },
                StrigoiEchoWebhookController::healthyPayload);
        return ResponseEntity.ok(out);
    }
}
