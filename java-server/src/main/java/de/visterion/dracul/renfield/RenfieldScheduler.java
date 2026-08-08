package de.visterion.dracul.renfield;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.hunting.news.NewsEventTagger;
import de.visterion.dracul.hunting.news.NewsEventType;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import de.visterion.dracul.position.PositionMath;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.vistierie.VistierieRunDetail;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Daily pre-market watchlist review trigger (D4/D6): assembles the full input for the
 * renfield agent — the LLM gets facts, not tool access — and fires an on-demand
 * Vistierie run WITH the completion webhook (R3; a triggered run does not fall back to
 * the registered webhook). Never throws out of the scheduled method; an unreachable
 * Vistierie is a WARN and the next day's cron retries naturally.
 */
@Component
@ConditionalOnProperty(value = "dracul.renfield.enabled", havingValue = "true")
public class RenfieldScheduler {

    private static final Logger log = LoggerFactory.getLogger(RenfieldScheduler.class);

    /** How long a run-context snapshot is kept. Only the action-check of its own run reads it,
     *  minutes after the trigger; 30 days is pure forensic headroom. */
    private static final int RUN_CONTEXT_RETENTION_DAYS = 30;

    private final WatchlistRepository watchlist;
    private final AgoraMarketData marketData;
    private final AgoraCompanyData companyData;
    private final DaywalkerAlertRepository alerts;
    private final VerdictRepository verdicts;
    private final HeldPositionService heldPositions;
    private final PortfolioWeights portfolioWeights;
    private final SectorResolver sectors;
    private final VistierieClient vistierie;
    private final HiveMemResearchService memory;
    private final TradeProposalRepository proposals;
    private final RenfieldRunContextRepository runContext;
    private final FxService fx;
    private final String publicUrl;
    private final String webhookToken;
    private final String connection;
    private final String owner;
    private final int maxSymbols;
    private final long priorMemoryBudgetMs;
    private final NewsEventTagger tagger = new NewsEventTagger();

    public RenfieldScheduler(WatchlistRepository watchlist, AgoraMarketData marketData,
            AgoraCompanyData companyData, DaywalkerAlertRepository alerts,
            VerdictRepository verdicts, HeldPositionService heldPositions,
            PortfolioWeights portfolioWeights, SectorResolver sectors,
            VistierieClient vistierie, HiveMemResearchService memory,
            TradeProposalRepository proposals, RenfieldRunContextRepository runContext,
            FxService fx,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.renfield.webhook-token:dev-token-change-me}") String webhookToken,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.renfield.max-symbols:30}") int maxSymbols,
            @Value("${dracul.renfield.prior-memory-budget-ms:2000}") long priorMemoryBudgetMs) {
        this.watchlist = watchlist;
        this.marketData = marketData;
        this.companyData = companyData;
        this.alerts = alerts;
        this.verdicts = verdicts;
        this.heldPositions = heldPositions;
        this.portfolioWeights = portfolioWeights;
        this.sectors = sectors;
        this.vistierie = vistierie;
        this.memory = memory;
        this.proposals = proposals;
        this.runContext = runContext;
        this.fx = fx;
        this.publicUrl = publicUrl;
        this.webhookToken = webhookToken;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.maxSymbols = maxSymbols;
        this.priorMemoryBudgetMs = priorMemoryBudgetMs;
    }

    // zone is mandatory here — codebase precedent is split and the spec pins UTC.
    @Scheduled(cron = "${dracul.renfield.cron:0 0 12 * * MON-FRI}", zone = "UTC")
    public void run() {
        try {
            var items = watchlist.findAllByUser(owner);
            if (items.isEmpty()) {
                log.info("renfield: watchlist empty — skipping today's review (no run, no message)");
                return;
            }
            List<WatchlistItem> selected = items;
            if (items.size() > maxSymbols) {
                selected = items.stream()
                        .sorted(Comparator.comparingInt(RenfieldScheduler::priorityRank)
                                .thenComparing(WatchlistItem::addedAt, Comparator.reverseOrder()))
                        .limit(maxSymbols)
                        .toList();
                log.info("renfield: capped watchlist review to {} of {} symbols (dropped {})",
                        maxSymbols, items.size(), items.size() - maxSymbols);
            }
            var assembled = assemble(selected, Instant.now());
            var detail = vistierie.triggerRun("renfield", assembled.input(),
                    publicUrl + "/api/renfield/complete", webhookToken);
            log.info("renfield review triggered for {} watchlist symbols", selected.size());
            snapshotRunContext(detail, assembled);
        } catch (RuntimeException e) {
            log.warn("renfield trigger failed: {}", e.getMessage());
        }
    }

    /**
     * Persists what was held at trigger time, so the action-check (F4) judges a proposal
     * against the state the agent actually reasoned about rather than a depot that may have
     * changed -- or failed to load -- by the time the completion arrives. Best-effort and
     * separately caught: the run is already away, so a DB hiccup here must not masquerade as
     * "renfield trigger failed". Older rows are swept in the same step.
     */
    private void snapshotRunContext(VistierieRunDetail detail, Assembled assembled) {
        String runId = detail == null ? null : detail.id();
        if (runId == null || runId.isBlank()) {
            log.warn("renfield: no run id returned — skipping the run-context snapshot "
                    + "(the action check will report the snapshot as missing)");
            return;
        }
        try {
            runContext.save(runId, assembled.heldBySymbol(), assembled.positionSource());
            int purged = runContext.deleteOlderThan(RUN_CONTEXT_RETENTION_DAYS);
            if (purged > 0) {
                log.info("renfield: purged {} run-context rows older than {} days",
                        purged, RUN_CONTEXT_RETENTION_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("renfield: run-context snapshot for run {} failed: {}", runId, e.getMessage());
        }
    }

    /** The assembled payload plus the two facts the snapshot needs but the payload nests. */
    record Assembled(Map<String, Object> input, Map<String, Boolean> heldBySymbol,
                     String positionSource) {}

    Map<String, Object> assembleInput(List<WatchlistItem> items, Instant now) {
        return assemble(items, now).input();
    }

    Assembled assemble(List<WatchlistItem> items, Instant now) {
        // "the depot is empty" and "the depot is down" must never read the same: the payload
        // says which of the two it was, and the prompt tells the agent to lean on `holding`
        // when the broker view is unavailable.
        HeldPositionService.OpenPositions depot = heldPositions.openPositionsOrUnavailable(connection);
        List<HeldPosition> open = depot.positions();
        String positionSource = depot.available() ? "ok" : "unavailable";
        Map<String, BigDecimal> weights = portfolioWeights.weightsBySymbol(open);
        Map<String, HeldPosition> heldBySymbol = new LinkedHashMap<>();
        for (HeldPosition p : PortfolioWeights.collapseBySymbol(open)) {
            heldBySymbol.putIfAbsent(p.symbol(), p);
        }
        Map<String, Quote> quotes = marketData.quotes(
                items.stream().map(WatchlistItem::ticker).toList());
        Instant since = now.minus(24, ChronoUnit.HOURS);
        LocalDate to = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate from = to.minusDays(1);

        // Task 11 (spec §11): wall-clock-only short-circuit. searchForInput never throws (it
        // degrades to List.of() internally), so there is no exception to catch here -- once the
        // budget is spent, remaining symbols simply skip the call and get an empty prior_memory.
        long priorMemoryDeadline = System.nanoTime() + priorMemoryBudgetMs * 1_000_000L;

        // F2: ONE batched query for the whole review — a per-symbol lookup would be 60 round
        // trips per run at the prod cap.
        //
        // The query runs BEFORE triggerRun and sits inside run()'s blanket catch, so letting it
        // escape would turn a hiccup on `trade_proposals` into no run, no payload, no digest —
        // the silent day this repair exists to remove, for a table the trigger never needed
        // before. Degrading to `[]` is equally wrong: it reads exactly like "nothing was
        // proposed yesterday". So the third path, the same shape as `position_source` and the
        // house convention of the daily analysis (design §E): the key is OMITTED and a
        // top-level marker says the lookup failed.
        Map<String, List<PriorProposal>> priorProposals;
        String priorProposalsSource;
        try {
            priorProposals = proposals.findPriorBySymbols(
                    owner, items.stream().map(WatchlistItem::ticker).toList());
            priorProposalsSource = "ok";
        } catch (RuntimeException e) {
            log.warn("renfield: prior-proposal lookup failed — reviewing without proposal history: {}",
                    e.getMessage());
            priorProposals = null;
            priorProposalsSource = "unavailable";
        }

        Map<String, Boolean> heldForSnapshot = new LinkedHashMap<>();
        var symbols = new ArrayList<Map<String, Object>>();
        for (WatchlistItem item : items) {
            var m = new LinkedHashMap<String, Object>();
            m.put("symbol", item.ticker());
            m.put("company_name", item.companyName());
            Quote q = quotes.get(item.ticker());
            m.put("current_price", q != null ? q.price() : item.currentPrice());
            m.put("day_change_percent", q != null ? q.dayChangePercent() : item.dayChangePercent());
            // T2.2 (D5): the bare `held` boolean is REPLACED by a real position block; absent
            // block = not held. gain_loss_pct uses the C1 snapshot formula (|mv|/|qty| vs
            // avgPrice) — renfield has no trigger close.
            HeldPosition p = heldBySymbol.get(item.ticker());
            if (p != null) {
                String direction = PositionMath.direction(p.quantity());
                var pos = new LinkedHashMap<String, Object>();
                pos.put("direction", direction);
                pos.put("entry", p.avgPrice());
                pos.put("gain_loss_pct", PositionMath.gainLossPct(direction, p.avgPrice(),
                        PositionMath.perUnitPrice(p.marketValue(), p.quantity())));
                pos.put("weight_pct", weights.get(item.ticker()));
                pos.put("active_stop", p.activeStop() != null ? p.activeStop() : p.initialStop());
                pos.put("sector", sectors.sector(item.ticker()));
                m.put("position", pos);
            } else {
                String sector = sectors.sector(item.ticker());
                if (sector != null) m.put("sector", sector);
            }
            // F1: what the USER holds, from the HELD tag of the owner-scoped watchlist row —
            // independent of, and printed next to, the broker `position` block above. The five
            // paper positions in the depot are not the seventeen real holdings.
            Map<String, Object> holding = holdingFor(item, q);
            if (holding != null) m.put("holding", holding);
            heldForSnapshot.put(item.ticker(), holding != null || p != null);
            m.put("news", newsFor(item.ticker(), from, to));
            m.put("alerts", alertsFor(item.ticker(), since));
            if (item.verdictId() != null) {
                verdicts.findLatestBySymbol(item.ticker()).ifPresent(v -> {
                    var vm = new LinkedHashMap<String, Object>();
                    vm.put("horizon", v.horizon());
                    vm.put("summary", v.summary());
                    vm.put("signals", v.signals());
                    vm.put("risks", v.risks());
                    m.put("verdict", vm);
                });
            }
            List<Map<String, Object>> priorMemory = System.nanoTime() < priorMemoryDeadline
                    ? memory.searchForInput(item.ticker(), 3).stream()
                            .map(h -> Map.<String, Object>of("summary", h.summary(), "content", h.content()))
                            .toList()
                    : List.of();
            m.put("prior_memory", priorMemory);
            if (priorProposals != null) {
                m.put("prior_proposals", priorProposals.getOrDefault(item.ticker(), List.of()).stream()
                        .map(pp -> {
                            var e = new LinkedHashMap<String, Object>();
                            e.put("date", pp.date());
                            e.put("action", pp.action());
                            e.put("confidence", pp.confidence());
                            return (Map<String, Object>) e;
                        })
                        .toList());
            }
            symbols.add(m);
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("as_of", now.toString());
        input.put("position_source", positionSource);
        input.put("prior_proposals_source", priorProposalsSource);
        input.put("symbols", symbols);
        return new Assembled(input, heldForSnapshot, positionSource);
    }

    /**
     * The user's own holding for one watchlist row, or null when the row is not tagged HELD.
     * Built ONLY from the owner-scoped {@code items} list handed to {@link #assemble} — a
     * fresh watchlist query would hand a second account's entry prices and share counts to
     * the LLM.
     *
     * <p>Most HELD rows carry neither entry price nor share count; the block still exists,
     * just with fewer fields — "held, details unknown" and "not held" must not look alike.
     *
     * <p>Currency: the entry sits in {@code entry_currency}, the quote in {@code currency},
     * and on prod those differ (EUR entry, USD quote). A percentage across the two would be
     * part FX move, so it is only emitted once a real rate exists. The decision is taken on
     * {@link FxService#hasRate} and NEVER on the return value of
     * {@link FxService#convert}, which hands back the unchanged amount on a cache miss and
     * is then indistinguishable from a genuine 1:1 conversion.
     */
    private Map<String, Object> holdingFor(WatchlistItem item, Quote quote) {
        if (!"HELD".equals(item.tag())) return null;
        // Belt to the owner-scoped braces above: a row belonging to somebody else contributes
        // no holding, whatever put it into the list.
        if (item.owner() != null && !item.owner().equals(owner)) return null;
        var h = new LinkedHashMap<String, Object>();
        if (item.entryPrice() != null) h.put("entry_price", item.entryPrice());
        if (notBlank(item.entryCurrency())) h.put("entry_currency", item.entryCurrency());
        if (item.shareCount() != null) h.put("share_count", item.shareCount());
        if (notBlank(item.currency())) h.put("currency", item.currency());

        String from = item.entryCurrency();
        String to = item.currency();
        if (item.entryPrice() == null || !notBlank(from) || !notBlank(to) || !fx.hasRate(from, to)) {
            return h;
        }
        BigDecimal entry = BigDecimal.valueOf(item.entryPrice());
        boolean sameCurrency = from.equalsIgnoreCase(to);
        BigDecimal entryInQuoteCurrency = sameCurrency ? entry : fx.convert(entry, from, to);
        if (entryInQuoteCurrency == null || entryInQuoteCurrency.signum() <= 0) return h;
        if (!sameCurrency) h.put("entry_price_in_quote_currency", entryInQuoteCurrency);

        BigDecimal current = quote != null && quote.price() != null
                ? quote.price()
                : BigDecimal.valueOf(item.currentPrice());
        if (current.signum() > 0) {
            h.put("gain_loss_pct", current.subtract(entryInQuoteCurrency)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(entryInQuoteCurrency, 2, RoundingMode.HALF_UP));
        }
        return h;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private List<Map<String, Object>> newsFor(String symbol, LocalDate from, LocalDate to) {
        var out = new ArrayList<Map<String, Object>>();
        for (NewsHeadline h : companyData.news(symbol, from, to)) {
            var n = new LinkedHashMap<String, Object>();
            n.put("headline", h.headline());
            n.put("source", h.source());
            n.put("datetime", h.datetime() == null ? null : h.datetime().toString());
            n.put("credibility", h.credibility());
            Set<NewsEventType> tags = tagger.tag(h);
            if (!tags.isEmpty()) {
                n.put("event_tags", tags.stream().map(NewsEventType::wireValue)
                        .collect(Collectors.joining(",")));
            }
            out.add(n);
        }
        return out;
    }

    /**
     * Priority stage for the cap: lower rank = reviewed first when the watchlist exceeds
     * {@code dracul.renfield.max-symbols}. First matching stage wins; verdict is
     * {@code verdictId != null}, NOT {@code source == "verdict"} -- the two diverge once a
     * verdict is merged onto a manual watchlist row.
     */
    private static int priorityRank(WatchlistItem i) {
        if ("HELD".equals(i.tag())) return 0;
        if (i.verdictId() != null) return 1;
        String s = i.source();
        if (s != null && s.startsWith("agent:")) return 2;
        if ("manual".equals(s)) return 3;
        if ("seed".equals(s)) return 4;
        return 5; // total fallback: lowest priority
    }

    private List<Map<String, Object>> alertsFor(String symbol, Instant since) {
        var out = new ArrayList<Map<String, Object>>();
        for (var a : alerts.recentAlerts(symbol, since)) {
            var m = new LinkedHashMap<String, Object>();
            m.put("trigger_type", a.triggerType());
            m.put("severity", a.severity());
            m.put("thesis", a.thesis());
            m.put("created_at", a.createdAt().toString());
            out.add(m);
        }
        return out;
    }
}
