package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.*;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.Form4Filing;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic detection over the depot's live positions (via {@link HeldPositionService}).
 * Fetches the shared market-wide Form-4 window once per poll (via Agora), runs the four
 * detectors once per distinct depot symbol (triggers are market-wide), and emits a
 * per-position event whenever that (owner, symbol, trigger_type) is outside its cooldown.
 * All external fetches degrade to empty on failure -- the poll never crashes the Vistierie
 * scheduler tick, and a depot-down {@link HeldPositionService#openPositions} simply yields
 * no positions to fan over (not an error).
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
    private final String connection;
    private final String owner;
    private final double priceThreshold;
    private final double volumeMultiplier;
    private final long cooldownSeconds;

    private final PriceVolumeDetector priceVolume = new PriceVolumeDetector();
    private final InsiderSellDetector insiderSell = new InsiderSellDetector();
    private final NewsDetector news = new NewsDetector();
    private final DowngradeDetector downgrade = new DowngradeDetector();

    public DaywalkerEventEngine(
            HeldPositionService heldPositions, WatchlistRepository watchlist,
            AgoraIntraday intraday,
            AgoraCompanyData companyData, AgoraFilings filings,
            DaywalkerAlertRepository alerts,
            @Value("${dracul.daywalker.price-spike-threshold:0.03}") double priceThreshold,
            @Value("${dracul.daywalker.volume-spike-multiplier:3.0}") double volumeMultiplier,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser) {
        this.heldPositions = heldPositions;
        this.watchlist = watchlist;
        this.intraday = intraday;
        this.companyData = companyData;
        this.filings = filings;
        this.alerts = alerts;
        this.priceThreshold = priceThreshold;
        this.volumeMultiplier = volumeMultiplier;
        this.cooldownSeconds = cooldownSeconds;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
    }

    public List<TriggerEvent> detect(Instant since, Instant now) {
        var positions = heldPositions.openPositions(connection);
        Map<String, HeldPosition> repBySymbol = new LinkedHashMap<>();
        for (HeldPosition p : positions) {
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
        if (universe.isEmpty()) return List.of();

        Instant effectiveSince = since != null ? since : now.minusSeconds(3600);
        LocalDate fromDate = effectiveSince.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = now.atZone(ZoneOffset.UTC).toLocalDate();

        List<Form4Filing> form4;
        try {
            form4 = filings.recentForm4(fromDate, toDate).items();
        } catch (Exception e) {
            log.warn("Form-4 fetch failed during Daywalker poll: {}", e.getMessage());
            form4 = List.of();
        }

        var out = new ArrayList<TriggerEvent>();
        for (WatchlistItem item : universe.values()) {
            var candidates = new ArrayList<TriggerEvent>();
            candidates.addAll(priceVolume.detect(item,
                    intraday.candles(item.ticker()), priceThreshold, volumeMultiplier));
            insiderSell.detect(item, form4).ifPresent(candidates::add);
            news.detect(item, companyData.news(item.ticker(), fromDate, toDate)).ifPresent(candidates::add);
            downgrade.detect(item, companyData.recommendations(item.ticker())).ifPresent(candidates::add);
            if (candidates.isEmpty()) continue;

            HeldPosition rep = repBySymbol.get(item.ticker());
            for (TriggerEvent base : candidates) {
                if (!inCooldown(owner, item.ticker(), base.triggerType(), now)) {
                    out.add(rep != null ? enrich(base, rep) : base);
                }
            }
        }
        return out;
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

    /** Enrich a market-wide trigger with the position's stored stop/entry context. The context
     *  block (active/initial stop) is nullable as a group -- a depot position with no open
     *  {@code position_context} row still gets this event, just without a breach verdict. */
    private TriggerEvent enrich(TriggerEvent base, HeldPosition position) {
        BigDecimal close = base.currentPrice();
        BigDecimal activeStop = position.activeStop() != null ? position.activeStop() : position.initialStop();
        BigDecimal entry = position.avgPrice();
        BigDecimal gainLossPct = (close != null && entry != null && entry.signum() != 0)
                ? close.subtract(entry).divide(entry, MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100)) : null;
        var ctx = new PositionContext(entry, gainLossPct, activeStop, null, null, null);
        String breached = BreachedLevel.evaluate(close, activeStop, null);
        return new TriggerEvent(base.symbol(), base.companyName(), base.triggerType(),
                base.currentPrice(), base.detail(), position.symbol(), ctx, breached);
    }

    private boolean inCooldown(String owner, String symbol, TriggerType type, Instant now) {
        return alerts.lastAlertAt(owner, symbol, type.name())
                .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                .orElse(false);
    }
}
