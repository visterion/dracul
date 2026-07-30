package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.echo.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-echo")
public class StrigoiEchoWebhookController extends HuntController {

    private final AgoraEarnings earnings;
    private final EchoPeadScreener screener;
    private final EchoEnrichmentService enrichment;
    private final AgoraCompanyData companyData;

    /** Safety bound on {@code fetch_candidate_news}, NOT a curation step. An uncapped 30-day
     *  news window for a heavily-covered symbol (150-300 items, ~350-450 B each ≈ 60-120 kB
     *  serialized) reproduces the exact overflow class this branch was built to escape: past
     *  ~95 kB the Claude-Max bridge offloads the tool result to a file the agent cannot read,
     *  and the agent silently sees nothing for that symbol. 40 is far above the ~7.7
     *  items/symbol average observed across candidates, so it does not restore the
     *  pre-selection the index/detail split deliberately removed — it only stops a single
     *  pathological symbol from blowing the bridge limit. */
    private static final int MAX_DETAIL_NEWS_ITEMS = 40;

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
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }

    /** Detail-Tool: die News EINES Symbols inkl. {@code summary}, gemappt auf {@link
     *  EchoNewsItem} (5 Felder, ohne {@code sourceType}/{@code url}/{@code domain}) und
     *  newest-first auf {@link #MAX_DETAIL_NEWS_ITEMS} gedeckelt (siehe dort) — beides nötig,
     *  damit dieser Endpoint nicht dieselbe Overflow-Klasse reproduziert, die der
     *  Index/Detail-Split eigentlich beheben sollte. Der Kandidaten-Payload
     *  trägt nur einen summary-losen Index (Spec 2026-07-27, §3.2); wer den Volltext braucht,
     *  zieht ihn hier nach. Läuft über denselben ToolFetchCache wie fetch-candidates, mit dem
     *  Symbol als paramsKey. {@code fetch_candidate_news} ist in {@link EchoDefaults} über den
     *  5-Parameter-{@code ToolCatalogEntry}-Konstruktor registriert, der {@code cacheable=true}
     *  bei der globalen Default-TTL setzt (siehe {@link de.visterion.dracul.agent.ToolCatalogEntry}) —
     *  {@link de.visterion.dracul.agent.ToolFetchCache} liefert also eine echte, von 0
     *  verschiedene TTL, und wiederholte Calls fürs selbe Symbol (gleicher {@code paramsKey})
     *  kosten innerhalb der TTL keinen zweiten Agora-Fetch. Health kommt aus {@link AgoraCompanyData#newsResult}, NICHT aus einem
     *  try/catch um {@link AgoraCompanyData#news} — {@code news()} verschluckt einen
     *  Agora-Ausfall selbst zu einer leeren Liste und würde ihn nie sichtbar machen. Ein
     *  {@code unavailable}-Ergebnis wird über {@link #healthyPayload} nicht gecacht, damit der
     *  nächste Call retried. */
    @PostMapping("/tools/fetch-news")
    public ResponseEntity<Map<String, Object>> fetchNews(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        final Map<String, Object> effectiveBody = body == null ? Map.of() : body;

        String symbol = null;
        String sinceRaw = null;
        if (effectiveBody.get("input") instanceof Map<?, ?> in) {
            if (in.get("symbol") != null) symbol = String.valueOf(in.get("symbol")).trim();
            if (in.get("since") != null) sinceRaw = String.valueOf(in.get("since")).trim();
        }
        if (symbol == null || symbol.isBlank()) {
            // source is agentName(), not "agora": this rejection happens before Agora is consulted
            // at all. source is a diagnostic field, so naming the provider here would send whoever
            // debugs it next to the wrong system — and it would contradict the catch below, which
            // reports agentName() for the same endpoint.
            return ok(unavailable(Map.of("news", List.of()), agentName(),
                    GUARD_MARKER + "blank symbol in tool input"));
        }

        // 30 days is the widest window this tool ever reads — the widest `since` an agent can
        // omit and still get a bounded fetch.
        LocalDate since = LocalDate.now().minusDays(30);
        if (sinceRaw != null && !sinceRaw.isBlank()) {
            try { since = LocalDate.parse(sinceRaw); }
            catch (Exception e) { /* unparsbares since → 30-Tage-Default, Obermenge */ }
        }
        final String sym = symbol;
        final LocalDate from = since;

        try {
            Map<String, Object> out = cache().get("fetch_candidate_news", sym + ":" + from,
                    () -> {
                        var result = companyData.newsResult(sym, from, LocalDate.now());
                        List<EchoNewsItem> items = result.items().stream()
                                .sorted(Comparator.comparing(NewsHeadline::datetime).reversed())
                                .limit(MAX_DETAIL_NEWS_ITEMS)
                                .map(h -> new EchoNewsItem(h.headline(), h.summary(), h.source(),
                                        h.credibility(), h.datetime()))
                                .toList();
                        Map<String, Object> output = new HashMap<>();
                        output.put("news", items);
                        output.put("data_source_health", healthOf(result.health()));
                        return Map.of("output", output);
                    },
                    StrigoiEchoWebhookController::healthyPayload);
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            // Same contract as HuntController#handleFetch: the catch sits outside cache.get so a
            // throw stores nothing, and a failing fetch degrades to "unavailable". Uncaught this
            // would be a 500 — Vistierie retries a 5xx once and then kills the run, so the prey
            // are lost either way; only the number of attempts differs from the 4xx case.
            log.warn("{} tool fetch_candidate_news failed for {} — answering unavailable instead of 4xx: {}",
                    agentName(), sym, e.toString(), e);
            return ok(unavailable(Map.of("news", List.of()), agentName(), GUARD_MARKER + e));
        }
    }

    @Override
    protected String outputKeyFor(org.springframework.web.method.HandlerMethod method) {
        return "fetchNews".equals(method.getMethod().getName()) ? "news" : super.outputKeyFor(method);
    }
}
