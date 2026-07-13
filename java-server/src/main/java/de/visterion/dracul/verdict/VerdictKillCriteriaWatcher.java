package de.visterion.dracul.verdict;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.events.VerdictKillCriteriaBreachedEvent;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic (no-LLM) watcher: for every live depot position carrying research context
 *  (a linked verdict + its kill criteria, copied into {@code position_context} at entry time),
 *  evaluates the kill criteria against the current quote and persists any breach on the linked
 *  verdict. Runs after US close, before gropar. Never throws out of the scheduled method; a
 *  failure on one position never blocks the rest.
 *
 * <p>Positions are read from {@link HeldPositionService} (depot ⨝ {@code position_context}),
 * not the watchlist -- the depot is the single source of truth for what's held. A position
 * with no context row, no kill criteria, or no linked verdict is skipped: there is nothing to
 * watch (or nowhere to persist a breach), and that is never an error. */
@Component
@ConditionalOnProperty(value = "dracul.verdict-killwatch.enabled", havingValue = "true", matchIfMissing = true)
public class VerdictKillCriteriaWatcher {

    private static final Logger log = LoggerFactory.getLogger(VerdictKillCriteriaWatcher.class);

    private final HeldPositionService heldPositions;
    private final VerdictRepository verdictRepo;
    private final AgoraMarketData marketData;
    private final KillCriteriaEvaluator evaluator;
    private final ApplicationEventPublisher events;
    private final String connection;
    private final String owner;

    public VerdictKillCriteriaWatcher(HeldPositionService heldPositions, VerdictRepository verdictRepo,
            AgoraMarketData marketData, KillCriteriaEvaluator evaluator,
            ApplicationEventPublisher events,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser) {
        this.heldPositions = heldPositions;
        this.verdictRepo = verdictRepo;
        this.marketData = marketData;
        this.evaluator = evaluator;
        this.events = events;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
    }

    @Scheduled(cron = "${dracul.verdict-killwatch.cron:0 30 21 * * 1-5}", zone = "UTC")
    public void poll() {
        try {
            List<HeldPosition> watched = heldPositions.openPositions(connection).stream()
                    .filter(p -> p.killCriteria() != null && p.verdictId() != null)
                    .toList();
            if (watched.isEmpty()) return;

            Set<String> symbols = new LinkedHashSet<>();
            for (HeldPosition p : watched) symbols.add(p.symbol());
            Map<String, Quote> quotes = marketData.quotes(symbols);

            for (HeldPosition p : watched) {
                try {
                    checkOne(p, quotes.get(p.symbol()));
                } catch (RuntimeException e) {
                    log.warn("verdict-killwatch: failed to check position {} (verdict {}): {}",
                            p.symbol(), p.verdictId(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("verdict-killwatch poll failed", e);
        }
    }

    private void checkOne(HeldPosition p, Quote quote) {
        if (quote == null || quote.price() == null) return;

        List<String> allCriteria = toStringList(p.killCriteria());
        if (allCriteria.isEmpty()) return;

        List<String> freshlyBreached = evaluator.breached(allCriteria, quote.price());

        // Breaches are CUMULATIVE: a kill criterion is a falsifiable thesis-death condition —
        // once breached, the thesis is dead; a price recovery never un-breaches it. Persisting
        // the union also means a re-dip can never count as "newly breached" again (no
        // flapping/duplicate SSE events).
        List<String> alreadyBreached = verdictRepo.killCriteriaBreachedFor(p.verdictId());
        List<String> newlyBreached = freshlyBreached.stream()
                .filter(c -> !alreadyBreached.contains(c))
                .toList();
        List<String> cumulative = new ArrayList<>(alreadyBreached);
        cumulative.addAll(newlyBreached);

        verdictRepo.markKillCriteriaBreached(p.verdictId(), cumulative);

        if (!newlyBreached.isEmpty()) {
            events.publishEvent(new VerdictKillCriteriaBreachedEvent(
                    owner, p.verdictId(), p.symbol(), newlyBreached));
        }
    }

    /** {@code position_context.kill_criteria} as a flat string list; empty for null/non-array
     *  JSON (defensive -- the column is written as a JSON array by {@code PositionReconciler}). */
    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) out.add(n.asText(""));
        }
        return out;
    }
}
