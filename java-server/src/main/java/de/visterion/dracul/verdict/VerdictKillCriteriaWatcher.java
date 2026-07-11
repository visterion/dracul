package de.visterion.dracul.verdict;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.events.VerdictKillCriteriaBreachedEvent;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic (no-LLM) watcher: for every open (non-DISMISSed), non-held verdict, evaluates
 *  the contributing prey's kill criteria against the current quote and persists any breach.
 *  Runs after US close, before gropar. Never throws out of the scheduled method; a failure on
 *  one verdict never blocks the rest. */
@Component
@ConditionalOnProperty(value = "dracul.verdict-killwatch.enabled", havingValue = "true", matchIfMissing = true)
public class VerdictKillCriteriaWatcher {

    private static final Logger log = LoggerFactory.getLogger(VerdictKillCriteriaWatcher.class);

    private final VerdictRepository verdictRepo;
    private final WatchlistRepository watchlistRepo;
    private final PreyRepository preyRepo;
    private final AgoraMarketData marketData;
    private final KillCriteriaEvaluator evaluator;
    private final ApplicationEventPublisher events;

    public VerdictKillCriteriaWatcher(VerdictRepository verdictRepo, WatchlistRepository watchlistRepo,
            PreyRepository preyRepo, AgoraMarketData marketData, KillCriteriaEvaluator evaluator,
            ApplicationEventPublisher events) {
        this.verdictRepo = verdictRepo;
        this.watchlistRepo = watchlistRepo;
        this.preyRepo = preyRepo;
        this.marketData = marketData;
        this.evaluator = evaluator;
        this.events = events;
    }

    @Scheduled(cron = "${dracul.verdict-killwatch.cron:0 30 21 * * 1-5}", zone = "UTC")
    public void poll() {
        try {
            List<VerdictRepository.OpenVerdictForCheck> open = verdictRepo.findOpenForKillCheck();
            if (open.isEmpty()) return;

            Set<String> heldSymbols = watchlistRepo.findAll().stream()
                    .filter(this::isHeld)
                    .map(WatchlistItem::ticker)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            List<VerdictRepository.OpenVerdictForCheck> watched = open.stream()
                    .filter(v -> !heldSymbols.contains(v.symbol()))
                    .toList();
            if (watched.isEmpty()) return;

            Set<String> symbols = new LinkedHashSet<>();
            for (var v : watched) symbols.add(v.symbol());
            Map<String, Quote> quotes = marketData.quotes(symbols);

            for (var v : watched) {
                try {
                    checkOne(v, quotes.get(v.symbol()));
                } catch (RuntimeException e) {
                    log.warn("verdict-killwatch: failed to check verdict {} ({}): {}",
                            v.id(), v.symbol(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("verdict-killwatch poll failed", e);
        }
    }

    private void checkOne(VerdictRepository.OpenVerdictForCheck v, Quote quote) {
        if (quote == null || quote.price() == null) return;

        List<Prey> prey = preyRepo.findByIds(v.contributingPreyIds());
        List<String> allCriteria = new ArrayList<>();
        for (Prey p : prey) {
            if (p.killCriteria() != null) allCriteria.addAll(p.killCriteria());
        }
        if (allCriteria.isEmpty()) return;

        List<String> breached = evaluator.breached(allCriteria, quote.price());

        verdictRepo.markKillCriteriaBreached(v.id(), breached);

        List<String> alreadyBreached = v.alreadyBreached() == null ? List.of() : v.alreadyBreached();
        List<String> newlyBreached = breached.stream()
                .filter(c -> !alreadyBreached.contains(c))
                .toList();
        if (!newlyBreached.isEmpty()) {
            events.publishEvent(new VerdictKillCriteriaBreachedEvent(
                    v.userId(), v.id(), v.symbol(), newlyBreached));
        }
    }

    private boolean isHeld(WatchlistItem item) {
        return "HELD".equals(item.tag())
                && item.entryPrice() != null
                && item.shareCount() != null;
    }
}
