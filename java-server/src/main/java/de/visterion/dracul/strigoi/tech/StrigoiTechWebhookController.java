package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * strigoi-tech (spec 2026-10-03 §4): builds the conviction basket. Two uncached tools —
 * {@code fetch_tech_book} (the basket's state) and {@code check_tech_candidate} (one symbol with
 * the code verdict) — and a completion that, in this order: (1) status check (base), (2) flags
 * catastrophe exits UNCONDITIONALLY ({@link #onCompletionAccepted}, before the base's
 * empty-prey/duplicate early returns — R2 Major 1), (3) re-validates and caps the picks
 * ({@link #selectForPersist}), (4) persists prey and (5) emits signals (base).
 *
 * <p>Health notes of a completion ({@code picks_over_cap}, {@code ineligible_pick},
 * {@code catastrophe_rejected}, {@code executor_disabled}) go to one WARN line and into the next
 * {@code fetch_tech_book} as {@code book.last_completion_notes}.
 */
@RestController
@ConditionalOnProperty(value = "dracul.strigoi.tech.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-tech")
public class StrigoiTechWebhookController extends HuntController {

    static final String AGENT = "strigoi-tech";
    private static final List<String> NOTE_KEYS = List.of("picks_over_cap", "ineligible_pick",
            "catastrophe_rejected", "executor_disabled");
    private static final int BOOK_NEWS_LOOKBACK_DAYS = 3;
    private static final int BOOK_NEWS_MAX = 5;

    private final TechBookService book;
    private final TechCandidateService candidates;
    private final TechSettings settings;
    private final ObjectProvider<ExecutorPositionRepository> positions;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Map<String, Integer>> notesInFlight = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastCompletionNotes = new AtomicReference<>();

    public StrigoiTechWebhookController(
            @Value("${dracul.strigoi.tech.webhook-token}") String token,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            TechBookService book,
            TechCandidateService candidates,
            TechSettings settings,
            ObjectProvider<ExecutorPositionRepository> positions) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.book = book;
        this.candidates = candidates;
        this.settings = settings;
        this.positions = positions;
    }

    @Override protected String agentName() { return AGENT; }
    @Override protected String defaultAnomalyType() { return ExitProfile.TECH_CONVICTION; }
    @Override protected String defaultHorizon() { return "12m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return TechDefaults.FETCH_BOOK; }
    @Override protected String fetchOutputKey() { return "book"; }

    @Override
    protected String outputKeyFor(HandlerMethod method) {
        return "checkCandidate".equals(method.getMethod().getName()) ? "candidate" : "book";
    }

    /** Not routed: both strigoi-tech tools have their own, uncached endpoints below. */
    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> input) {
        return DataSourceResult.unavailable("dracul", "strigoi-tech has no generic fetch");
    }

    @PostMapping("/tools/fetch-book")
    public ResponseEntity<Map<String, Object>> fetchBook(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        try {
            return ok(bookPayload(book.snapshot()));
        } catch (RuntimeException e) {
            log.warn("{} tool {} failed — answering unavailable instead of 4xx: {}", AGENT,
                    TechDefaults.FETCH_BOOK, e.toString(), e);
            return ok(unavailable(Map.of("book", Map.of()), AGENT, GUARD_MARKER + e));
        }
    }

    @PostMapping("/tools/check-candidate")
    public ResponseEntity<Map<String, Object>> checkCandidate(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        String symbol = symbolOf(body);
        if (symbol == null) {
            return ok(unavailable(Map.of("candidate", Map.of()), AGENT,
                    GUARD_MARKER + "missing symbol"));
        }
        try {
            TechCandidateService.Candidate c = candidates.check(symbol, book.snapshot());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("candidate", c.payload());
            if (c.sourceUnavailable()) {
                // Profile AND quote both failed with an Agora outage: the verdict's
                // data_unavailable reasons say nothing about the symbol. Reported as a source
                // outage (not tool-guard: the endpoint itself worked) so a dead Agora never
                // reads as "every name was ineligible tonight".
                log.warn("{} tool {}: data source DOWN for {} — profile and quote both failed, "
                        + "the verdict is not about the symbol", AGENT, TechDefaults.CHECK_CANDIDATE,
                        symbol);
                out.put("data_source_health", healthOf(DataSourceHealth.unavailable("agora",
                        "agora: profile and quote both unavailable for " + symbol)));
            } else {
                out.put("data_source_health", healthOf(DataSourceHealth.healthy("agora")));
            }
            return ok(out);
        } catch (RuntimeException e) {
            log.warn("{} tool {} failed — answering unavailable instead of 4xx: {}", AGENT,
                    TechDefaults.CHECK_CANDIDATE, e.toString(), e);
            return ok(unavailable(Map.of("candidate", Map.of()), AGENT, GUARD_MARKER + e));
        }
    }

    private static String symbolOf(Map<String, Object> body) {
        if (body == null) return null;
        Object raw = body.get("input") instanceof Map<?, ?> in ? in.get("symbol") : body.get("symbol");
        if (!(raw instanceof String s) || s.isBlank()) return null;
        return s.trim().toUpperCase(Locale.ROOT);
    }

    Map<String, Object> bookPayload(TechBookService.Snapshot s) {
        List<String> symbols = s.convictionOpen().stream().map(ExecutorPosition::symbol).toList();
        Map<String, BigDecimal> prices = candidates.lastPrices(symbols);
        List<Map<String, Object>> open = new ArrayList<>();
        List<String> unpriced = new ArrayList<>();
        List<String> newsless = new ArrayList<>();
        for (ExecutorPosition p : s.convictionOpen()) {
            BigDecimal close = prices.get(p.symbol());
            if (close == null) unpriced.add(p.symbol());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", p.symbol());
            m.put("entry_price", p.entryPrice());
            m.put("qty", p.qty());
            m.put("highest_close", p.highestPrice());
            m.put("current_close", close);
            m.put("pl_pct", close == null || p.entryPrice() == null || p.entryPrice().signum() == 0
                    ? null
                    : close.divide(p.entryPrice(), 6, RoundingMode.HALF_UP)
                            .subtract(BigDecimal.ONE).movePointRight(2)
                            .setScale(2, RoundingMode.HALF_UP));
            m.put("active_stop", p.activeStop());
            m.put("half_sold", p.trimCount() > 0);
            m.put("days_held", daysHeld(p.entryDate()));
            m.put("catastrophe_flagged", p.catastropheReason() != null);
            TechCandidateService.NewsRead news =
                    candidates.bookNews(p.symbol(), BOOK_NEWS_LOOKBACK_DAYS, BOOK_NEWS_MAX);
            m.put("news_available", news.available());
            m.put("news_since_last_run", news.items());
            if (!news.available()) newsless.add(p.symbol());
            open.add(m);
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (ExecutorSignal sig : s.techPending()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", sig.symbol());
            m.put("signal_id", sig.signalId());
            m.put("created_at", sig.createdAt());
            pending.add(m);
        }
        Map<String, Object> bookMap = new LinkedHashMap<>();
        bookMap.put("open_positions", open);
        bookMap.put("pending_signals", pending);
        bookMap.put("basket_size", settings.basketSize());
        bookMap.put("slots_free", s.slotsFree(settings.basketSize()));
        bookMap.put("new_names_allowed_this_week", s.newAllowedThisWeek(settings.maxNewPerWeek()));
        bookMap.put("accepted_this_week", s.acceptedThisWeek());
        bookMap.put("recently_exited", s.recentlyExited().stream().sorted().toList());
        bookMap.put("executor_available", s.executorAvailable());
        bookMap.put("last_completion_notes", lastCompletionNotes.get());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("book", bookMap);
        // Degraded, never "unavailable": every hunter prompt answers "unavailable" with
        // {"prey": []}, and the two halves of the book are independent — a get_quote outage says
        // nothing about the news the catastrophe check reads, and missing news for one position
        // says nothing about the others. partial + detail name exactly what is missing; each
        // position also carries news_available so the LLM knows which ones it cannot judge.
        List<String> missing = new ArrayList<>();
        if (!unpriced.isEmpty()) {
            missing.add("no current price for " + unpriced.size() + " of " + symbols.size()
                    + " open position(s): " + String.join(",", unpriced));
        }
        if (!newsless.isEmpty()) {
            missing.add("news unavailable for " + newsless.size() + " of " + symbols.size()
                    + " open position(s) — not judgeable for a catastrophe tonight: "
                    + String.join(",", newsless));
        }
        if (missing.isEmpty()) {
            out.put("data_source_health", healthOf(DataSourceHealth.healthy("dracul")));
        } else {
            String detail = "agora: " + String.join("; ", missing);
            log.warn("{} tool {}: book degraded — {}", AGENT, TechDefaults.FETCH_BOOK, detail);
            out.put("data_source_health", healthOf(DataSourceHealth.degraded("agora", detail,
                    true, false)));
        }
        return out;
    }

    private long daysHeld(String entryDate) {
        if (entryDate == null || entryDate.length() < 10) return 0;
        try {
            LocalDate entry = LocalDate.parse(entryDate.substring(0, 10));
            return Duration.between(entry.atStartOfDay(),
                    LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay()).toDays();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Completion (spec §4.3/§4.4)
    // ---------------------------------------------------------------------------------------

    private static String runKey(String runId) {
        return runId == null ? "-" : runId;
    }

    private Map<String, Integer> notes(String runId) {
        return notesInFlight.computeIfAbsent(runKey(runId), k -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            NOTE_KEYS.forEach(n -> m.put(n, 0));
            return m;
        });
    }

    @Override
    protected void onCompletionAccepted(JsonNode body, String runId) {
        notesInFlight.remove(runKey(runId));
        Map<String, Integer> notes = notes(runId);
        JsonNode exits = body.path("output").path("catastrophe_exits");
        if (!exits.isArray() || exits.isEmpty()) return;

        ExecutorPositionRepository repo = positions.getIfAvailable();
        if (repo == null) {
            notes.merge("executor_disabled", exits.size(), Integer::sum);
            log.warn("{} run {}: {} catastrophe exit(s) dropped — executor disabled",
                    AGENT, runId, exits.size());
            return;
        }
        int flagged = 0, already = 0, rejected = 0;
        for (JsonNode e : exits) {
            String symbol = e.path("symbol").asString("").trim();
            String reason = e.path("reason").asString("").trim();
            if (symbol.isEmpty() || reason.isEmpty()) {
                rejected++;
                log.warn("{} run {}: catastrophe exit without symbol/reason rejected: {}",
                        AGENT, runId, e);
                continue;
            }
            ExecutorPosition p = repo.findOpenBySymbolIgnoreCase(settings.executorConnection(), symbol);
            if (p == null || p.exitProfile() != ExitProfile.CONVICTION) {
                rejected++;
                log.warn("{} run {}: catastrophe exit for {} rejected — no OPEN CONVICTION position "
                        + "on {}", AGENT, runId, symbol, settings.executorConnection());
                continue;
            }
            if (p.catastropheReason() != null) {
                already++;
                continue;
            }
            String full = reason + evidenceSuffix(e.path("evidence"));
            if (full.length() > 1000) full = full.substring(0, 1000);
            if (repo.flagCatastrophe(p.id(), settings.executorConnection(), full, clock.instant())) {
                flagged++;
            } else {
                already++;
            }
        }
        notes.merge("catastrophe_rejected", rejected, Integer::sum);
        log.info("{} run {}: catastrophe exits flagged={} already_flagged={} rejected={}",
                AGENT, runId, flagged, already, rejected);
    }

    private static String evidenceSuffix(JsonNode evidence) {
        if (!evidence.isArray() || evidence.isEmpty()) return "";
        List<String> items = new ArrayList<>();
        evidence.forEach(n -> items.add(n.asString("")));
        return " [evidence: " + String.join("; ", items) + "]";
    }

    @Override
    protected List<Prey> selectForPersist(List<Prey> mapped, JsonNode body, String runId) {
        Map<String, Integer> notes = notes(runId);
        List<Prey> kept = List.of();
        if (!mapped.isEmpty()) {
            TechBookService.Snapshot snap = book.snapshot();
            List<Prey> eligible = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int ineligible = 0;
            for (Prey p : mapped) {
                String symbol = p.symbol().trim().toUpperCase(Locale.ROOT);
                if (!seen.add(symbol)) {
                    ineligible++;
                    log.warn("{} run {}: duplicate pick {} dropped", AGENT, runId, symbol);
                    continue;
                }
                TechEligibility.Verdict v = candidates.eligibility(symbol, snap);
                if (!v.eligible()) {
                    ineligible++;
                    log.warn("{} run {}: pick {} dropped — ineligible: {}", AGENT, runId, symbol,
                            v.reasons());
                    continue;
                }
                eligible.add(p);
            }
            int capacity = snap.capacity(settings.basketSize(), settings.maxNewPerWeek());
            int overCap = Math.max(0, eligible.size() - capacity);
            kept = List.copyOf(eligible.subList(0, Math.min(capacity, eligible.size())));
            if (overCap > 0) {
                log.warn("{} run {}: {} pick(s) over cap dropped (capacity {} = min(slots {}, week {}))",
                        AGENT, runId, overCap, capacity, snap.slotsFree(settings.basketSize()),
                        snap.newAllowedThisWeek(settings.maxNewPerWeek()));
            }
            notes.merge("picks_over_cap", overCap, Integer::sum);
            notes.merge("ineligible_pick", ineligible, Integer::sum);
        }
        publishNotes(runId);
        return kept;
    }

    private void publishNotes(String runId) {
        Map<String, Integer> notes = notesInFlight.remove(runKey(runId));
        if (notes == null) return;
        StringBuilder sb = new StringBuilder("run=").append(runKey(runId));
        boolean any = false;
        for (String key : NOTE_KEYS) {
            int v = notes.getOrDefault(key, 0);
            sb.append(' ').append(key).append('=').append(v);
            any |= v > 0;
        }
        lastCompletionNotes.set(sb.toString());
        if (any) log.warn("strigoi-tech completion notes: {}", sb);
    }

    /** The notes of the most recent completion — {@code book.last_completion_notes}. */
    String lastCompletionNotes() {
        return lastCompletionNotes.get();
    }
}
