package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.*;
import de.visterion.dracul.hunting.edgar.EdgarFormFourAdapter;
import de.visterion.dracul.hunting.edgar.Form4Filing;
import de.visterion.dracul.hunting.finnhub.FinnhubNewsAdapter;
import de.visterion.dracul.hunting.yahoo.YahooIntradayAdapter;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic detection over all users' watchlists. Fetches shared EDGAR
 * filings once per poll, runs the four detectors once per distinct ticker
 * (triggers are market-wide), and emits a trigger only when at least one owner
 * of that ticker is outside its per-(owner, symbol, trigger_type) cooldown. All
 * external fetches degrade to empty on failure — the poll never crashes the
 * Vistierie scheduler tick.
 */
@Component
public class DaywalkerEventEngine {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerEventEngine.class);

    private final WatchlistRepository watchlist;
    private final YahooIntradayAdapter yahoo;
    private final FinnhubNewsAdapter finnhub;
    private final EdgarFormFourAdapter edgar;
    private final DaywalkerAlertRepository alerts;
    private final double priceThreshold;
    private final double volumeMultiplier;
    private final long cooldownSeconds;

    private final PriceVolumeDetector priceVolume = new PriceVolumeDetector();
    private final InsiderSellDetector insiderSell = new InsiderSellDetector();
    private final NewsDetector news = new NewsDetector();
    private final DowngradeDetector downgrade = new DowngradeDetector();

    public DaywalkerEventEngine(
            WatchlistRepository watchlist, YahooIntradayAdapter yahoo,
            FinnhubNewsAdapter finnhub, EdgarFormFourAdapter edgar,
            DaywalkerAlertRepository alerts,
            @Value("${dracul.daywalker.price-spike-threshold:0.03}") double priceThreshold,
            @Value("${dracul.daywalker.volume-spike-multiplier:3.0}") double volumeMultiplier,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds) {
        this.watchlist = watchlist;
        this.yahoo = yahoo;
        this.finnhub = finnhub;
        this.edgar = edgar;
        this.alerts = alerts;
        this.priceThreshold = priceThreshold;
        this.volumeMultiplier = volumeMultiplier;
        this.cooldownSeconds = cooldownSeconds;
    }

    public List<TriggerEvent> detect(Instant since, Instant now) {
        var items = watchlist.findAll();
        if (items.isEmpty()) return List.of();

        // Detect once per distinct ticker (triggers are market-wide); track owners for cooldown.
        Map<String, WatchlistItem> repByTicker = new LinkedHashMap<>();
        Map<String, List<String>> ownersByTicker = new HashMap<>();
        for (WatchlistItem it : items) {
            repByTicker.putIfAbsent(it.ticker(), it);
            ownersByTicker.computeIfAbsent(it.ticker(), k -> new ArrayList<>()).add(it.owner());
        }

        Instant effectiveSince = since != null ? since : now.minusSeconds(3600);
        LocalDate fromDate = effectiveSince.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = now.atZone(ZoneOffset.UTC).toLocalDate();

        List<Form4Filing> filings;
        try {
            filings = edgar.recentFilings(fromDate, toDate).items();
        } catch (Exception e) {
            log.warn("EDGAR fetch failed during Daywalker poll: {}", e.getMessage());
            filings = List.of();
        }

        var out = new ArrayList<TriggerEvent>();
        for (WatchlistItem item : repByTicker.values()) {
            var candidates = new ArrayList<TriggerEvent>();
            candidates.addAll(priceVolume.detect(item,
                    yahoo.intradayCandles(item.ticker()), priceThreshold, volumeMultiplier));
            insiderSell.detect(item, filings).ifPresent(candidates::add);
            news.detect(item, finnhub.companyNews(item.ticker(), fromDate, toDate)).ifPresent(candidates::add);
            downgrade.detect(item, finnhub.recommendationTrend(item.ticker())).ifPresent(candidates::add);

            var owners = ownersByTicker.get(item.ticker());
            for (TriggerEvent ev : candidates) {
                if (anyOwnerOutsideCooldown(owners, item.ticker(), ev.triggerType(), now)) out.add(ev);
            }
        }
        return out;
    }

    /** True if at least one owner of the ticker is outside cooldown for this (symbol, type). */
    private boolean anyOwnerOutsideCooldown(List<String> owners, String symbol, TriggerType type, Instant now) {
        for (String owner : owners) {
            boolean inCooldown = alerts.lastAlertAt(owner, symbol, type.name())
                    .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                    .orElse(false);
            if (!inCooldown) return true;
        }
        return false;
    }
}
