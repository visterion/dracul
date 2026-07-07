package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.*;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.Form4Filing;
import de.visterion.dracul.watchlist.PositionRisk;
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

/**
 * Deterministic detection over all users' watchlists. Fetches the shared market-wide Form-4
 * window once per poll (via Agora), runs the four detectors once per distinct ticker
 * (triggers are market-wide), and emits a trigger only when at least one owner of that
 * ticker is outside its per-(owner, symbol, trigger_type) cooldown. All external fetches
 * degrade to empty on failure — the poll never crashes the Vistierie scheduler tick.
 */
@Component
public class DaywalkerEventEngine {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerEventEngine.class);

    private final WatchlistRepository watchlist;
    private final AgoraIntraday intraday;
    private final AgoraCompanyData companyData;
    private final AgoraFilings filings;
    private final DaywalkerAlertRepository alerts;
    private final double priceThreshold;
    private final double volumeMultiplier;
    private final long cooldownSeconds;

    private final PriceVolumeDetector priceVolume = new PriceVolumeDetector();
    private final InsiderSellDetector insiderSell = new InsiderSellDetector();
    private final NewsDetector news = new NewsDetector();
    private final DowngradeDetector downgrade = new DowngradeDetector();

    public DaywalkerEventEngine(
            WatchlistRepository watchlist, AgoraIntraday intraday,
            AgoraCompanyData companyData, AgoraFilings filings,
            DaywalkerAlertRepository alerts,
            @Value("${dracul.daywalker.price-spike-threshold:0.03}") double priceThreshold,
            @Value("${dracul.daywalker.volume-spike-multiplier:3.0}") double volumeMultiplier,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds) {
        this.watchlist = watchlist;
        this.intraday = intraday;
        this.companyData = companyData;
        this.filings = filings;
        this.alerts = alerts;
        this.priceThreshold = priceThreshold;
        this.volumeMultiplier = volumeMultiplier;
        this.cooldownSeconds = cooldownSeconds;
    }

    public List<TriggerEvent> detect(Instant since, Instant now) {
        var items = watchlist.findAll();
        if (items.isEmpty()) return List.of();

        Map<String, PositionRisk> riskById = watchlist.positionRiskByItemId();

        Map<String, WatchlistItem> repByTicker = new LinkedHashMap<>();
        Map<String, List<WatchlistItem>> itemsByTicker = new LinkedHashMap<>();
        for (WatchlistItem it : items) {
            repByTicker.putIfAbsent(it.ticker(), it);
            itemsByTicker.computeIfAbsent(it.ticker(), k -> new ArrayList<>()).add(it);
        }

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
        for (WatchlistItem rep : repByTicker.values()) {
            var candidates = new ArrayList<TriggerEvent>();
            candidates.addAll(priceVolume.detect(rep,
                    intraday.candles(rep.ticker()), priceThreshold, volumeMultiplier));
            insiderSell.detect(rep, form4).ifPresent(candidates::add);
            news.detect(rep, companyData.news(rep.ticker(), fromDate, toDate)).ifPresent(candidates::add);
            downgrade.detect(rep, companyData.recommendations(rep.ticker())).ifPresent(candidates::add);
            if (candidates.isEmpty()) continue;

            var tickerItems = itemsByTicker.get(rep.ticker());
            for (TriggerEvent base : candidates) {
                out.addAll(fanOut(base, tickerItems, riskById, now));
            }
        }
        return out;
    }

    /** Fan a market-wide trigger into per-HELD-position enriched events + one generic
     *  event for the watch-only owners (each gated by that owner's cooldown). */
    private List<TriggerEvent> fanOut(TriggerEvent base, List<WatchlistItem> tickerItems,
            Map<String, PositionRisk> riskById, Instant now) {
        var events = new ArrayList<TriggerEvent>();
        boolean anyWatchOnlyFree = false;
        for (WatchlistItem it : tickerItems) {
            boolean free = !inCooldown(it.owner(), it.ticker(), base.triggerType(), now);
            if (isHeld(it)) {
                if (free) events.add(enrich(base, it, riskById.get(it.id())));
            } else if (free) {
                anyWatchOnlyFree = true;
            }
        }
        if (anyWatchOnlyFree) events.add(base);
        return events;
    }

    private TriggerEvent enrich(TriggerEvent base, WatchlistItem item, PositionRisk pr) {
        BigDecimal close = pr != null ? pr.currentClose() : base.currentPrice();
        BigDecimal activeStop = pr == null ? null : pr.activeStop();
        BigDecimal nextTarget = pr == null ? null : pr.nextTarget2r();
        BigDecimal atr = pr == null ? null : pr.atr();
        BigDecimal entry = BigDecimal.valueOf(item.entryPrice());
        BigDecimal gainLossPct = (close != null && entry.signum() != 0)
                ? close.subtract(entry).divide(entry, java.math.MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100)) : null;
        BigDecimal distToStopInAtr = (close != null && activeStop != null
                && atr != null && atr.signum() != 0)
                ? close.subtract(activeStop).divide(atr, java.math.MathContext.DECIMAL64) : null;
        var ctx = new PositionContext(entry, gainLossPct, activeStop, nextTarget, atr, distToStopInAtr);
        String breached = BreachedLevel.evaluate(close, activeStop, nextTarget);
        return new TriggerEvent(base.symbol(), base.companyName(), base.triggerType(),
                base.currentPrice(), base.detail(), item.id(), ctx, breached);
    }

    private static boolean isHeld(WatchlistItem it) {
        return "HELD".equals(it.tag()) && it.entryPrice() != null && it.shareCount() != null;
    }

    private boolean inCooldown(String owner, String symbol, TriggerType type, Instant now) {
        return alerts.lastAlertAt(owner, symbol, type.name())
                .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                .orElse(false);
    }
}
