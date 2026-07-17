package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.*;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.Form4Filing;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import de.visterion.dracul.position.PositionMath;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Deterministic detection over the union of the depot's live positions (via
 * {@link HeldPositionService}) and the watchlist — deduped per symbol, with the depot
 * representative winning when a symbol appears in both. Fetches the shared market-wide
 * Form-4 window once per poll (via Agora), runs the four detectors once per distinct
 * symbol (triggers are market-wide), and emits an event whenever that
 * (symbol, trigger_type) pair — owner-agnostic — is outside its cooldown. All external
 * fetches degrade to empty on failure -- the poll never crashes the Vistierie scheduler
 * tick, and a depot-down {@link HeldPositionService#openPositions} simply yields no
 * depot positions to fan over (not an error); a non-empty watchlist still sweeps.
 */
@Component
public class DaywalkerEventEngine {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerEventEngine.class);

    private final HeldPositionService heldPositions;
    private final WatchlistRepository watchlist;
    private final AgoraIntraday intraday;
    private final AgoraCompanyData companyData;
    private final AgoraFilings filings;
    private final DaywalkerAlertRepository alerts;
    private final PortfolioWeights portfolioWeights;
    private final SectorResolver sectors;
    private final String connection;
    private final double priceThreshold;
    private final double volumeMultiplier;
    private final long cooldownSeconds;
    private final long pollBudgetMs;

    private final PriceVolumeDetector priceVolume = new PriceVolumeDetector();
    private final InsiderSellDetector insiderSell = new InsiderSellDetector();
    private final NewsDetector news = new NewsDetector();
    private final DowngradeDetector downgrade = new DowngradeDetector();

    public DaywalkerEventEngine(
            HeldPositionService heldPositions, WatchlistRepository watchlist,
            AgoraIntraday intraday,
            AgoraCompanyData companyData, AgoraFilings filings,
            DaywalkerAlertRepository alerts,
            PortfolioWeights portfolioWeights, SectorResolver sectors,
            @Value("${dracul.daywalker.price-spike-threshold:0.03}") double priceThreshold,
            @Value("${dracul.daywalker.volume-spike-multiplier:3.0}") double volumeMultiplier,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds,
            @Value("${dracul.daywalker.poll-budget-ms:60000}") long pollBudgetMs,
            @Value("${dracul.position.connection:depot-1}") String connection) {
        this.heldPositions = heldPositions;
        this.watchlist = watchlist;
        this.intraday = intraday;
        this.companyData = companyData;
        this.filings = filings;
        this.alerts = alerts;
        this.portfolioWeights = portfolioWeights;
        this.sectors = sectors;
        this.priceThreshold = priceThreshold;
        this.volumeMultiplier = volumeMultiplier;
        this.cooldownSeconds = cooldownSeconds;
        this.pollBudgetMs = pollBudgetMs;
        this.connection = connection;
    }

    /** Immutable per-poll sweep inputs, prepared inside the budget. repBySymbol holds ONE
     *  COLLAPSED entry per symbol (multi-lot rule A1); weights comes from the FULL lot list. */
    private record SweepPlan(Map<String, WatchlistItem> universe, Map<String, HeldPosition> repBySymbol,
                             Map<String, BigDecimal> weights,
                             List<Form4Filing> form4, LocalDate fromDate, LocalDate toDate) {}

    private record SymbolScan(List<TriggerEvent> events, List<MacroHeadline> macroHeadlines) {}

    public List<TriggerEvent> detect(Instant since, Instant now) {
        // The budget wraps the WHOLE pass — including openPositions, the watchlist query and
        // the shared Form-4 fetch — because the events poll runs synchronously inside
        // Vistierie's single scheduler tick; a degraded Agora must not stall all agents.
        long deadlineNanos = System.nanoTime() + pollBudgetMs * 1_000_000L;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<SweepPlan> planFuture = exec.submit(() -> plan(since, now));
            SweepPlan plan;
            try {
                plan = planFuture.get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                planFuture.cancel(true);
                log.warn("Daywalker poll budget of {} ms exhausted before the sweep started — "
                        + "skipping all symbols this poll", pollBudgetMs);
                return List.of();
            }
            if (plan.universe().isEmpty()) return List.of();

            List<Future<SymbolScan>> futures = new ArrayList<>();
            for (WatchlistItem item : plan.universe().values()) {
                futures.add(exec.submit(() -> detectSymbol(item, plan, now)));
            }
            var out = new ArrayList<TriggerEvent>();
            var macro = new ArrayList<MacroHeadline>();
            int skipped = 0;
            for (Future<SymbolScan> f : futures) {
                try {
                    SymbolScan scan = f.get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
                    out.addAll(scan.events());
                    macro.addAll(scan.macroHeadlines());
                } catch (TimeoutException e) {
                    f.cancel(true);
                    skipped++;
                } catch (ExecutionException e) {
                    log.warn("Daywalker per-symbol detection failed: {}",
                            e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
            if (skipped > 0) {
                log.warn("Daywalker poll budget of {} ms exhausted — skipped {} of {} symbols this poll",
                        pollBudgetMs, skipped, futures.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            log.warn("Daywalker poll failed: {}", e.getMessage());
            return List.of();
        } finally {
            exec.shutdownNow();
        }
    }

    private static long remaining(long deadlineNanos) {
        return Math.max(1, deadlineNanos - System.nanoTime());
    }

    private SweepPlan plan(Instant since, Instant now) {
        var positions = heldPositions.openPositions(connection);
        // Weight map from the FULL lot list (spec §9 Engine wiring: putIfAbsent-style rep
        // selection keeps only the first lot; the denominator must sum ALL lots).
        Map<String, BigDecimal> weights = portfolioWeights.weightsBySymbol(positions);
        Map<String, HeldPosition> repBySymbol = new LinkedHashMap<>();
        for (HeldPosition p : PortfolioWeights.collapseBySymbol(positions)) {
            repBySymbol.putIfAbsent(p.symbol(), p);
        }

        // Universe = depot ∪ watchlist, deduped per symbol; the depot representative wins
        // (it carries positionId → position context in the LLM prompt). D2: no hunt-prey
        // expansion. An empty depot with a non-empty watchlist still sweeps.
        Map<String, WatchlistItem> universe = new LinkedHashMap<>();
        for (HeldPosition rep : repBySymbol.values()) {
            universe.put(rep.symbol(), asDetectorItem(rep));
        }
        for (WatchlistRepository.SweepRow row : watchlist.distinctSweepRows()) {
            universe.putIfAbsent(row.ticker(), asDetectorItem(row));
        }

        Instant effectiveSince = since != null ? since : now.minusSeconds(3600);
        LocalDate fromDate = effectiveSince.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = now.atZone(ZoneOffset.UTC).toLocalDate();

        List<Form4Filing> form4 = List.of();
        if (!universe.isEmpty()) {
            try {
                form4 = filings.recentForm4(fromDate, toDate).items();
            } catch (Exception e) {
                log.warn("Form-4 fetch failed during Daywalker poll: {}", e.getMessage());
            }
        }
        return new SweepPlan(universe, repBySymbol, weights, form4, fromDate, toDate);
    }

    /** Per-symbol detector pass — runs on a virtual thread. The shared detector fields
     *  (priceVolume, insiderSell, news, downgrade — incl. their NewsEventTagger) are
     *  stateless; pinned by NewsEventTaggerConcurrencyTest. */
    private SymbolScan detectSymbol(WatchlistItem item, SweepPlan plan, Instant now) {
        var candidates = new ArrayList<TriggerEvent>();
        candidates.addAll(priceVolume.detect(item,
                intraday.candles(item.ticker()), priceThreshold, volumeMultiplier));
        insiderSell.detect(item, plan.form4()).ifPresent(candidates::add);
        NewsScanResult newsScan = news.detect(item,
                companyData.news(item.ticker(), plan.fromDate(), plan.toDate()));
        newsScan.trigger().ifPresent(candidates::add);
        downgrade.detect(item, companyData.recommendations(item.ticker())).ifPresent(candidates::add);

        // T2.2 Part B (round 1, m5): resolve INSIDE the symbol's own virtual thread, for EVERY
        // swept symbol — this warms the cache in parallel under the poll budget so the portfolio
        // snapshot (cache-only read) finds sectors even for symbols without a trigger this poll.
        String sector = sectors.sector(item.ticker());

        if (candidates.isEmpty()) return new SymbolScan(List.of(), newsScan.macroOnly());

        HeldPosition rep = plan.repBySymbol().get(item.ticker());
        var out = new ArrayList<TriggerEvent>();
        for (TriggerEvent base : candidates) {
            if (!inCooldown(item.ticker(), base.triggerType(), now)) {
                if (rep != null) {
                    out.add(enrich(base, rep, plan.weights().get(item.ticker()), sector));
                } else {
                    out.add(withDetailSector(base, sector));
                }
            }
        }
        return new SymbolScan(out, newsScan.macroOnly());
    }

    /** Build the minimal legacy-shaped item the shared (unmodified) detectors expect --
     *  ticker/companyName/currentPrice only -- from a depot position; no watchlist row backs
     *  this any more, so the unused watchlist-only fields (tag, entry, owner, ...) are null. */
    private static WatchlistItem asDetectorItem(HeldPosition p) {
        double price = p.avgPrice() != null ? p.avgPrice().doubleValue() : 0.0;
        return new WatchlistItem(p.symbol(), p.symbol(), p.symbol(), price, 0.0,
                null, null, null, null, List.of(), List.of(), null, null, null, null, null);
    }

    /** Watchlist-only sweep representative: real refreshed current_price, no position context. */
    private static WatchlistItem asDetectorItem(WatchlistRepository.SweepRow r) {
        return new WatchlistItem(r.ticker(), r.ticker(), r.companyName(), r.currentPrice(), 0.0,
                null, null, null, null, List.of(), List.of(), null, null, null, null, null);
    }

    /** Enrich a market-wide trigger with the position's stored stop/entry context, direction-aware
     *  (T2.2). Zero/blank quantity → direction null, long math (status quo), one DEBUG line. */
    private TriggerEvent enrich(TriggerEvent base, HeldPosition position,
                                BigDecimal weightPct, String sector) {
        BigDecimal close = base.currentPrice();
        BigDecimal activeStop = position.activeStop() != null ? position.activeStop() : position.initialStop();
        BigDecimal entry = position.avgPrice();
        String direction = PositionMath.direction(position.quantity());
        if (direction == null) {
            log.debug("daywalker enrich: zero/blank quantity for {} — direction null, long math", position.symbol());
        }
        BigDecimal gainLossPct = PositionMath.gainLossPct(direction, entry, close);
        var ctx = new PositionContext(entry, gainLossPct, activeStop, null, null, null,
                direction, weightPct, sector);
        String breached = BreachedLevel.evaluate(close, activeStop, null, "short".equals(direction));
        return new TriggerEvent(base.symbol(), base.companyName(), base.triggerType(),
                base.currentPrice(), base.detail(), position.symbol(), ctx, breached);
    }

    /** Watchlist-only carrier (round 2, m-3): sector rides as a detail-map key — no new
     *  TriggerEvent record component, no factory ripple. Detector detail maps may be
     *  immutable, so copy before adding. Null sector → event unchanged. */
    private static TriggerEvent withDetailSector(TriggerEvent base, String sector) {
        if (sector == null) return base;
        var detail = new LinkedHashMap<>(base.detail());
        detail.put("sector", sector);
        return new TriggerEvent(base.symbol(), base.companyName(), base.triggerType(),
                base.currentPrice(), detail, base.positionId(), base.position(), base.breachedLevel());
    }

    private boolean inCooldown(String symbol, TriggerType type, Instant now) {
        return alerts.lastAlertAtAnyOwner(symbol, type.name())
                .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                .orElse(false);
    }
}
