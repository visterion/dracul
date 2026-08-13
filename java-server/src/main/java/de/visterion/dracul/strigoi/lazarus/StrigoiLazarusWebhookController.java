package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Quality-at-52-week-low hunter.
 *
 * <p><b>The universe is the market, not the watchlist (D7).</b> Until this fix the screened
 * universe WAS {@code watchlist_items}, and every run returned {@code candidates: []} with
 * {@code data_source_health.status = "healthy"}: a guaranteed no-op that read as a quiet market.
 * The table was NOT empty — it holds 52 rows. Lazarus was reading the wrong OWNER (see
 * {@link #owner}), which is a second, independent bug fixed alongside this one; taken together
 * they meant the watchlist-only universe could never have produced a candidate whatever the
 * table contained. The universe is now an index fetched from Agora
 * ({@code dracul.strigoi.lazarus.universe-source}, default {@code sp500}), with the watchlist
 * screened ON TOP of it, and an empty or unfetchable universe is reported as {@code unavailable}
 * — never as healthy-with-zero-candidates.
 *
 * <p><b>Two stages, because the cheap data and the expensive data come from different providers.</b>
 * {@code get_fundamentals} routes US symbols to Finnhub, throttled to 60 calls/minute across all
 * of Agora — one call per S&amp;P 500 member would spend eight minutes inside that throttle and
 * silently drop most of the universe. So {@link LazarusUniverseService} first narrows the index
 * on cheap 52-week-range probes — served by Agora's OHLC provider chain, Alpaca first, which is a
 * different and far less throttled source — and only the survivors (plus every watchlist name,
 * unconditionally) cost a fundamentals call. Since 2026-08-06 the pre-filter batches those probes
 * ({@code get_indicators_batch}, {@code probe-chunk-size} symbols per call), so the expected Agora
 * calls per run are 1 index + ceil(N / chunk-size) pre-filter calls (N = universe size, ~503 for
 * the S&amp;P 500, Wikipedia-sourced and cached 24 h inside Agora; ~5 calls at the default chunk
 * size, where it used to be ~503) + at most {@code fundamentals-max} fundamentals calls.
 *
 * <p><b>Nothing is lost quietly.</b> Pre-filter probe failures, missing fundamentals, a missing
 * 52-week low, enrichment drops, candidates that came back missing an enrichment source, a spent
 * pre-filter budget and both caps are counted and folded
 * into {@code partial} / {@code truncated} via {@link DataSourceHealth#degradedWith}. The
 * candidates that were found are always kept — the flags say "what you see is incomplete", not
 * "you saw nothing".
 *
 * <p><b>…but not everything lost is a degradation.</b> Index members younger than 52 weeks have no
 * 52-week range to compare against and never will until they age. They are counted separately
 * ({@code notEligible}) and kept OUT of {@code partial}: a flag that is raised by a permanent
 * property of three instruments fires every night and stops carrying information.
 *
 * <p><b>The same split applies one stage later, at the fundamentals (BUG-S29).</b> A null
 * {@code 52WeekLow} used to be counted as {@code no52wLow} whatever caused it, so an outage of
 * Agora's OHLC chain was recorded as a fact about the company. Since agora c89dba7 the metrics
 * blob carries a group-scoped marker when — and only when — the SOURCE failed:
 * {@code "52WeekRange": {"available": false, "error": "..."}}. Dracul reads it through
 * {@link BasicFinancials#week52RangeUnavailable()} and counts the two cases apart:
 * {@code no52wLowSourceFailed} is a degradation and drives {@code partial}, while
 * {@code no52wLow} is a statement about the instrument and lives in the log line only — exactly
 * the treatment {@code probeFailed} and {@code notEligible} already get in the pre-filter. Either
 * way the symbol is skipped, so no false candidate can arise from either. Until agora c89dba7 is
 * deployed the marker is never present and every such loss lands, as before, on {@code no52wLow}.
 *
 * <p>The screen thresholds themselves ({@code max-above-low}, the solvency gate, the P/B-or-P/FCF
 * cheapness gate in {@link LazarusScreener}) are UNCHANGED by this fix: they were never the bug.
 */
@RestController
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-lazarus")
public class StrigoiLazarusWebhookController extends HuntController {

    private static final String SOURCE = "agora";
    /** Universe-source value that turns the index off and restores the pre-D7 watchlist-only scope. */
    private static final String WATCHLIST_ONLY = "watchlist";

    private final WatchlistRepository watchlist;
    private final AgoraCompanyData companyData;
    private final LazarusScreener screener;
    private final LazarusEnrichmentService enrichment;
    private final LazarusListingResolver listingResolver;
    private final FxService fx;
    private final HeldPositionService heldPositionService;
    private final AgoraIndexConstituents indexConstituents;
    private final LazarusUniverseService universeService;
    private final String connection;
    /**
     * Owner whose watchlist is screened. NOT the literal {@code "default"} it used to be:
     * {@code LegacyWatchlistOwnerMigration} runs
     * {@code UPDATE watchlist_items SET user_id = :email WHERE user_id = 'default'} on every boot,
     * so production holds 52 watchlist rows and ZERO of them under {@code default}. Lazarus was
     * therefore screening an empty watchlist on every run — silently, which also made the
     * documented {@code universe-source: watchlist} fallback a fallback to nothing. Every sibling
     * (Renfield, gropar, daywalker, stopguard) already reads {@code dracul.primary-user-email};
     * lazarus was simply never migrated with them.
     */
    private final String owner;
    private final double maxAboveLow;
    private final double maxDebtEquity;
    private final double maxPriceToBook;
    private final double maxPFcf;
    private final double megaCapUsdMillions;
    private final String probeSymbol;
    private final String universeSource;
    private final int universeMax;
    private final double preFilterMargin;
    private final long preFilterBudgetMs;
    private final int maxConsecutiveDeadChunks;
    private final int fundamentalsMax;

    /**
     * Where the next pre-filter pass enters the universe. Advanced by however many symbols the
     * previous pass managed, so a budget that keeps truncating still covers the whole index over
     * successive runs instead of re-screening the same head of the alphabet forever. Deliberately
     * in-memory: a restart resetting the rotation costs one repeated slice, which is not worth a
     * table — and when the budget suffices (the normal case) the pass wraps the whole universe
     * anyway and the offset is irrelevant.
     */
    private final AtomicInteger rotationOffset = new AtomicInteger();

    public StrigoiLazarusWebhookController(
            @Value("${dracul.strigoi.lazarus.webhook-token}") String token,
            WatchlistRepository watchlist,
            AgoraCompanyData companyData,
            LazarusScreener screener,
            LazarusEnrichmentService enrichment,
            LazarusListingResolver listingResolver,
            FxService fx,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            HeldPositionService heldPositionService,
            AgoraIndexConstituents indexConstituents,
            LazarusUniverseService universeService,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.strigoi.lazarus.max-above-low:0.10}") double maxAboveLow,
            @Value("${dracul.strigoi.lazarus.max-debt-equity:3.0}") double maxDebtEquity,
            @Value("${dracul.strigoi.lazarus.max-price-to-book:2.0}") double maxPriceToBook,
            @Value("${dracul.strigoi.lazarus.max-p-fcf:20}") double maxPFcf,
            // Fallback matches application.yaml's 100000 on purpose: the yaml always wins in a real
            // deployment, so a differing literal here is dead config that only misleads whoever reads
            // it looking for the effective value.
            @Value("${dracul.strigoi.lazarus.mega-cap-usd-millions:100000}") double megaCapUsdMillions,
            @Value("${dracul.strigoi.lazarus.probe-symbol:AAPL}") String probeSymbol,
            @Value("${dracul.strigoi.lazarus.universe-source:sp500}") String universeSource,
            @Value("${dracul.strigoi.lazarus.universe-max:600}") int universeMax,
            @Value("${dracul.strigoi.lazarus.pre-filter-margin:0.25}") double preFilterMargin,
            // Fallback matches application.yaml's 240000 on purpose: the yaml always wins in a real
            // deployment, so a differing literal here is dead config that only misleads whoever reads
            // it looking for the effective value.
            @Value("${dracul.strigoi.lazarus.pre-filter-budget-ms:240000}") long preFilterBudgetMs,
            @Value("${dracul.strigoi.lazarus.max-consecutive-dead-chunks:2}") int maxConsecutiveDeadChunks,
            @Value("${dracul.strigoi.lazarus.fundamentals-max:60}") int fundamentalsMax) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.watchlist = watchlist;
        this.companyData = companyData;
        this.screener = screener;
        this.enrichment = enrichment;
        this.listingResolver = listingResolver;
        this.fx = fx;
        this.heldPositionService = heldPositionService;
        this.indexConstituents = indexConstituents;
        this.universeService = universeService;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.maxAboveLow = maxAboveLow;
        this.maxDebtEquity = maxDebtEquity;
        this.maxPriceToBook = maxPriceToBook;
        this.maxPFcf = maxPFcf;
        this.megaCapUsdMillions = megaCapUsdMillions;
        this.probeSymbol = probeSymbol;
        this.universeSource = universeSource;
        this.universeMax = universeMax;
        this.preFilterMargin = preFilterMargin;
        this.preFilterBudgetMs = preFilterBudgetMs;
        this.maxConsecutiveDeadChunks = maxConsecutiveDeadChunks;
        this.fundamentalsMax = fundamentalsMax;
    }

    @Override protected String agentName() { return "strigoi-lazarus"; }
    @Override protected String defaultAnomalyType() { return "QUALITY_52W_LOW"; }
    @Override protected String defaultHorizon() { return "12m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_quality_at_low_candidates"; }

    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> body) {
        // Dedup against the live depot: a name already held is not a "new" quality-at-low
        // candidate — surfacing it again would just recommend buying what's already owned. Symbols
        // are the join key (the depot has no watchlist_item_id concept); a depot-down fetch yields an
        // empty set (HeldPositionService is fail-soft), so dedup excludes nothing rather than erroring.
        Set<String> heldSymbols = heldPositionService.openPositions(connection).stream()
                .map(HeldPosition::symbol)
                .collect(Collectors.toSet());

        // Watchlist names are ALWAYS screened, whatever the index says and without paying the
        // pre-filter: a name the user tracks by hand is a stronger signal than any index membership.
        List<WatchlistItem> watchItems = watchlist.findAllByUser(owner).stream()
                .filter(item -> !heldSymbols.contains(item.ticker()))
                .toList();
        Set<String> watchSymbols = watchItems.stream()
                .map(item -> item.ticker() == null ? "" : item.ticker().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<IndexConstituent> universe = List.of();
        String indexDetail = null;
        int universeCapDropped = 0;
        boolean indexEnabled = !WATCHLIST_ONLY.equalsIgnoreCase(universeSource);
        if (indexEnabled) {
            var idx = indexConstituents.constituents(universeSource);
            if (idx.health().isHealthy()) {
                List<IndexConstituent> filtered = idx.items().stream()
                        .filter(c -> !heldSymbols.contains(c.symbol()))
                        .filter(c -> !watchSymbols.contains(c.symbol()))
                        .toList();
                if (filtered.size() > universeMax) {
                    universeCapDropped = filtered.size() - universeMax;
                    filtered = filtered.subList(0, universeMax);
                }
                universe = filtered;
            } else {
                indexDetail = idx.health().detail();
            }
        }

        // An empty universe is an OUTAGE, never a quiet market. This is the exact line the
        // production no-op was missing: with nothing to screen there is nothing to say about the
        // market, and saying "healthy, zero candidates" actively misleads the reasoning agent.
        if (universe.isEmpty() && watchItems.isEmpty()) {
            return DataSourceResult.unavailable(SOURCE, indexDetail != null
                    ? indexDetail + " and the watchlist is empty — no lazarus universe to screen"
                    : "lazarus universe is empty (source=" + universeSource + ", watchlist empty)");
        }

        var scan = universeService.preScreen(universe, preFilterMargin, preFilterBudgetMs,
                maxConsecutiveDeadChunks, rotationOffset.get());
        rotationOffset.addAndGet(Math.max(scan.screened(), 1));

        // Watchlist names first (unconditional), then the index shortlist closest to its low —
        // pctAboveLow ascending is the only meaningful priority available before any fundamentals
        // have been fetched, and it is the same ordering the enrichment cap uses.
        List<LazarusUniverseService.PreScreened> targets = new ArrayList<>();
        for (WatchlistItem item : watchItems) {
            targets.add(LazarusUniverseService.PreScreened.unconditional(
                    item.ticker(), item.companyName(), item.currentPrice()));
        }
        List<LazarusUniverseService.PreScreened> ranked = scan.shortlist().stream()
                .sorted(Comparator.comparingDouble(LazarusUniverseService.PreScreened::pctAboveLow))
                .toList();
        int room = Math.max(0, fundamentalsMax - targets.size());
        List<LazarusUniverseService.PreScreened> taken = ranked.stream().limit(room).toList();
        int fundamentalsCapDropped = ranked.size() - taken.size();
        targets.addAll(taken);

        // Single upfront reachability check (one per hunt, not per symbol), moved to just before
        // the expensive stage. fundamentals() alone can't tell "Agora is down" apart from "no data
        // for this symbol" — it collapses both to null — so a total outage would otherwise report
        // healthy with all-null financials. Probes a FIXED US canary
        // (dracul.strigoi.lazarus.probe-symbol, default AAPL), never targets.get(0): a non-US row
        // whose get_fundamentals is unavailable with the global-metrics flag OFF would wrongly
        // declare the whole hunt unavailable and kill the healthy US flow.
        if (!targets.isEmpty()) {
            var probe = companyData.fundamentalsResult(probeSymbol);
            if (!probe.health().isHealthy()) {
                return DataSourceResult.unavailable(SOURCE, probe.health().detail());
            }
        }

        var raws = new ArrayList<LazarusRaw>();
        int fundamentalsMissing = 0;
        int week52Missing = 0;
        int week52SourceFailed = 0;
        for (LazarusUniverseService.PreScreened p : targets) {
            BasicFinancials f = BasicFinancialsExtractor.extract(companyData.fundamentals(p.symbol()));
            if (f == null) {
                fundamentalsMissing++;
                continue;
            }
            if (f.week52Low() == null) {
                // The same split the pre-filter already draws between notEligible and probeFailed,
                // one stage later: a null 52-week low reaches us for two very different reasons and
                // used to land on one counter. With Agora's group-scoped marker present the SOURCE
                // failed while we asked (a degradation — a retry or a healthy provider would answer);
                // without it the value is genuinely absent for this INSTRUMENT, which no retry, no
                // failover and no bigger budget will ever change.
                if (f.week52RangeUnavailable()) {
                    week52SourceFailed++;
                    log.debug("strigoi-lazarus: 52-week range source unavailable for {}", p.symbol());
                } else {
                    week52Missing++;
                }
                continue;
            }
            raws.add(new LazarusRaw(p.symbol(), p.companyName(), p.currentPrice(), f));
        }
        var screenResult = screener.screen(raws, maxAboveLow, maxDebtEquity, maxPriceToBook, maxPFcf);

        // Listing resolution (Task 3): the screener cannot know which listing a candidate's
        // marketCap/reportingCurrency describe — that call happens here, once, for the survivors.
        var resolvedResult = listingResolver.resolve(screenResult.candidates());

        // Warm once per distinct non-USD reporting currency BEFORE converting: warm() never throws
        // (it logs and keeps the last-known rate), and hasRate() afterwards is the sole availability
        // signal — convert() cannot serve that role, since it silently returns the unconverted
        // amount on a cache miss (FxService:35).
        // Mirrors LazarusListingResolver's own "blank carries no evidence" rule
        // (LazarusListingResolver.java:78): a blank-but-non-null currency is not USD, but it is
        // also not a real ISO code to warm a rate for — without the isBlank() check this used to
        // fire a pointless get_fx_rate(from="") call (plus a WARN) once per night.
        resolvedResult.candidates().stream()
                .map(LazarusCandidate::reportingCurrency)
                .filter(c -> c != null && !c.isBlank() && !"USD".equalsIgnoreCase(c))
                .distinct()
                .forEach(c -> fx.warm(c, "USD"));

        // USD normalisation + the size decision, the single place both happen now (Task 4). A
        // candidate that cleared the cheapness gate on its own needs no size at all; everyone else
        // needs a USD figure at or above megaCapUsdMillions, and megaCapUsdMillions == 0 is the
        // documented off switch (a bare >= comparison would silently give every priced candidate
        // the exemption once size stops being a filter).
        List<LazarusCandidate> screened = new ArrayList<>();
        // Two DIFFERENT consequences of an unresolved listing, split AFTER the keep decision below
        // (not by LazarusListingResolver.Resolved.listingUnknown(), which counts before the keep
        // decision and therefore cannot tell them apart): a candidate that cleared the cheapness
        // gate on its own stays in the response regardless of its size — "what you see is
        // incomplete", still there, just without a size exemption or an Altman-Z. A candidate that
        // needed the size exemption and never got one because its listing could not be resolved is
        // GONE from the response — the harder case, a source outage that cost candidates outright.
        // Folding both into one counter (the pre-Task-4-followup shape) made a profile-endpoint
        // outage read as "N candidates have an unresolved listing" while items= silently shrank by
        // the same N, unreadable for an operator or the reasoning agent.
        int listingUnknownKept = 0;
        int listingUnknownDropped = 0;
        for (LazarusCandidate c : resolvedResult.candidates()) {
            Double usdMillions = null;
            boolean marketCapAvailable = false;
            if (c.marketCap() != null) {
                switch (c.listingResolution()) {
                    case FOREIGN_SUFFIXED -> {
                        String currency = c.reportingCurrency();
                        if (fx.hasRate(currency, "USD")) {
                            usdMillions = fx.convert(BigDecimal.valueOf(c.marketCap()), currency, "USD")
                                    .doubleValue();
                            marketCapAvailable = true;
                        }
                    }
                    case US_CONFIRMED -> {
                        // Defensive, not load-bearing today: LazarusListingResolver only ever
                        // assigns US_CONFIRMED when reportingCurrency was null/blank to begin with
                        // (a non-blank currency short-circuits straight to FOREIGN_SUFFIXED before
                        // any profile call), so this branch cannot currently see a non-USD
                        // currency. But that invariant lives in a DIFFERENT class — if a future
                        // change ever let US_CONFIRMED coexist with a set currency ("profile ticker
                        // matches, currency irrelevant"), a bare pass-through would silently read
                        // e.g. EUR millions as USD millions past the mega-cap threshold, which is
                        // exactly the bug this whole plan removes. Guard it here too.
                        String currency = c.reportingCurrency();
                        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
                            usdMillions = c.marketCap();
                            marketCapAvailable = true;
                        }
                    }
                    case UNKNOWN -> {
                        // no size can be trusted for an unresolved listing — stays unavailable.
                    }
                }
            }
            LazarusCandidate withUsd = c.withMarketCapUsd(usdMillions, marketCapAvailable);
            boolean keep = withUsd.cheapGatePassed()
                    || (marketCapAvailable && usdMillions >= megaCapUsdMillions && megaCapUsdMillions > 0);
            // Mirrors the resolver's own rule (LazarusListingResolver.java): only a candidate that
            // actually carried a market cap had anything to lose by staying unresolved, and a
            // foreign-suffixed or US-confirmed listing is not this loss at all — only UNKNOWN is.
            if (c.marketCap() != null && c.listingResolution() == ListingResolution.UNKNOWN) {
                if (keep) listingUnknownKept++; else listingUnknownDropped++;
            }
            if (keep) screened.add(withUsd);
        }

        var batch = enrichment.enrich(screened);
        var enriched = batch.candidates();
        // Two DIFFERENT losses, deliberately on two counters: enrichmentDropped are candidates that
        // are GONE (accruals hard-drop, enrichment cap) — a size difference; degradedCandidates are
        // candidates still in the list that came back missing a source, which a size difference
        // cannot see. Computed against `screened` (the list that actually entered enrich()), never
        // screenResult.candidates(): a size hope that was correctly filtered out above is not a
        // loss during enrichment, and counting it here would make `partial` permanent noise every
        // night the screener finds an uncheap, unconvertible or too-small candidate.
        int enrichmentDropped = screened.size() - enriched.size();

        // notEligible sits next to probeFailed on purpose: the two numbers used to be one, and an
        // operator reading this line has to be able to see at a glance that "6 symbols lost" was
        // three young listings and nothing broken.
        log.info("strigoi-lazarus universe: source={} universe={} screened={} shortlist={} "
                        + "watchlist={} fundamentals={} candidates={} (probeFailed={} notEligible={} "
                        + "noFundamentals={} no52wLow={} no52wLowSourceFailed={} "
                        + "implausibleRange={} foreignListing={} listingUnknownKept={} "
                        + "listingUnknownDropped={} "
                        + "enrichmentDropped={} enrichmentDegraded={} "
                        + "unscreened={} sourceDown={})",
                universeSource, universe.size(), scan.screened(), scan.shortlist().size(),
                watchItems.size(), targets.size(), enriched.size(), scan.probeFailed(),
                scan.notEligible(), fundamentalsMissing, week52Missing, week52SourceFailed,
                screenResult.implausibleRange(), resolvedResult.foreignListing(),
                listingUnknownKept, listingUnknownDropped,
                enrichmentDropped, batch.degradedCandidates(), scan.unscreened(), scan.sourceDown());

        return new DataSourceResult<>(enriched, health(indexDetail, scan, universeCapDropped,
                fundamentalsCapDropped, fundamentalsMissing, week52SourceFailed,
                listingUnknownKept, listingUnknownDropped, enrichmentDropped, batch.degradedCandidates()));
    }

    /**
     * Folds every counted loss into ONE health verdict via the shared
     * {@link DataSourceHealth#degradedWith} helper. Status stays {@code healthy} throughout —
     * every hunter prompt turns {@code unavailable} into "return exactly {@code {"prey": []}}",
     * which would throw away the candidates we did find. {@code partial} marks data we tried to
     * read and could not; {@code truncated} marks universe we deliberately did not read.
     */
    private DataSourceHealth health(String indexDetail, LazarusUniverseService.Scan scan,
                                    int universeCapDropped, int fundamentalsCapDropped,
                                    int fundamentalsMissing, int week52SourceFailed,
                                    int listingUnknownKept, int listingUnknownDropped,
                                    int enrichmentDropped, int enrichmentDegraded) {
        DataSourceHealth h = DataSourceHealth.healthy(SOURCE);
        if (indexDetail != null) {
            h = DataSourceHealth.degradedWith(h,
                    "index universe unavailable (" + indexDetail + "), watchlist only", true, false);
        }
        if (scan.sourceDown()) {
            h = DataSourceHealth.degradedWith(h,
                    "pre-filter source down after " + scan.screened() + " of " + scan.considered()
                            + " universe symbols", true, false);
        }
        // Only the DEGRADATION count reaches this flag. scan.notEligible() — index members younger
        // than 52 weeks — is deliberately absent: it is a permanent property of those instruments,
        // and folding it in here made every single run report partial=true (production 2026-08-05:
        // 490 of 490 screened, three young listings, partial=true), which turned the daily
        // analysis's "incomplete answer" alarm into permanent noise. It stays out of the detail
        // string too: DataSourceHealth.degradedWith only carries a detail together with a flag,
        // and the reasoning agent reads a detail as a description of what went wrong. The count
        // lives in the log line above, where an operator looks.
        if (scan.probeFailed() > 0) {
            h = DataSourceHealth.degradedWith(h,
                    // "no usable range", not "an unusable body": since the pre-filter batches, the
                    // count also covers symbols a chunk simply did not answer for. Both are the
                    // same thing to the reader — a symbol we tried to screen and could not.
                    scan.probeFailed() + " of " + scan.screened()
                            + " universe symbols returned no usable 52-week range (pre-filter)",
                    true, false);
        }
        if (scan.unscreened() > 0) {
            h = DataSourceHealth.degradedWith(h,
                    scan.unscreened() + " of " + scan.considered()
                            + " universe symbols left unscreened (budget)", false, true);
        }
        if (universeCapDropped > 0) {
            h = DataSourceHealth.degradedWith(h,
                    universeCapDropped + " universe symbols dropped by universe-max", false, true);
        }
        if (fundamentalsCapDropped > 0) {
            h = DataSourceHealth.degradedWith(h,
                    fundamentalsCapDropped + " pre-screened symbols dropped by the fundamentals budget",
                    false, true);
        }
        if (fundamentalsMissing > 0) {
            h = DataSourceHealth.degradedWith(h,
                    fundamentalsMissing + " symbols dropped: no fundamentals", true, false);
        }
        // week52Missing — the instrument genuinely has no 52-week low — is deliberately NOT folded
        // in, for the same reason scan.notEligible() is not: nothing failed, and no retry, failover
        // or bigger budget will produce a value. It is counted in the log line above, where an
        // operator looks. Only the SOURCE failure below is a degradation.
        if (week52SourceFailed > 0) {
            h = DataSourceHealth.degradedWith(h,
                    week52SourceFailed + " symbols dropped: 52-week range source unavailable",
                    true, false);
        }
        // foreignListing (a profile whose ticker names a different listing) is deliberately NOT
        // folded in here, for the same reason week52Missing and scan.notEligible() are not: it is
        // a PERMANENT property of the instrument (BRK.B trades under a different profile ticker
        // every night), not a lookup failure, and folding it in would turn `partial` into noise
        // that fires forever. It stays in the log line above.
        //
        // An unresolved listing IS a degradation — the profile lookup failed, was capped, or the
        // guard was already tripped, so this candidate's size could not be established — but it has
        // TWO different consequences that must not share one number. A candidate that cleared the
        // cheapness gate on its own stays in the response regardless of size: "what you see is
        // incomplete", still there, just without a size exemption or an Altman-Z (Task 5 gates Z on
        // the same resolution). A candidate that needed the size exemption and never got one because
        // its listing stayed unresolved is GONE from the response — a source outage that cost
        // candidates outright, the harder case. Folding both into one counter used to read as
        // "N candidates have an unresolved listing" while items= silently shrank by the same N,
        // unreadable for an operator or the reasoning agent trying to tell "still here, degraded"
        // from "missing entirely".
        if (listingUnknownKept > 0 || listingUnknownDropped > 0) {
            StringBuilder detail = new StringBuilder();
            if (listingUnknownKept > 0) {
                detail.append(listingUnknownKept)
                        .append(" candidates have an unresolved listing (no size exemption, no Altman-Z)");
            }
            if (listingUnknownDropped > 0) {
                if (detail.length() > 0) detail.append("; ");
                detail.append(listingUnknownDropped)
                        .append(" candidates were dropped for an unresolved listing (no size exemption possible)");
            }
            h = DataSourceHealth.degradedWith(h, detail.toString(), true, false);
        }
        if (enrichmentDropped > 0) {
            h = DataSourceHealth.degradedWith(h,
                    enrichmentDropped + " screened candidates dropped during enrichment", true, false);
        }
        // A candidate that lost only SOME of its enrichment is still returned, so the size-based
        // enrichmentDropped above cannot see it. Reported as partial ("what you see is
        // incomplete"), never as a drop — the candidate is there, some of its fields are not.
        if (enrichmentDegraded > 0) {
            h = DataSourceHealth.degradedWith(h,
                    enrichmentDegraded + " candidates lost at least one enrichment source", true, false);
        }
        return h;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
