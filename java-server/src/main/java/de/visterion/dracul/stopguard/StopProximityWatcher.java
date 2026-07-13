package de.visterion.dracul.stopguard;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic intraday watcher: every poll during the US session, classify each live depot
 *  position's price against its research-context active stop and emit a STOP_PROXIMITY /
 *  STOP_BREACHED alert. No LLM; gated off by default; never throws out of the scheduled
 *  method.
 *
 * <p>Positions are read from {@link HeldPositionService} (depot ⨝ {@code position_context}),
 * not the watchlist -- the depot is the single source of truth for what's held. A position
 * with no context row, or a context row with no {@code active_stop} yet (not frozen by gropar),
 * is skipped: there is nothing to compare against, and that is never an error.
 *
 * <p>{@code position_context} carries no ATR (that lives in the watchlist's gropar-written
 * snapshot, a data source this watcher no longer reads). {@link StopZoneEvaluator} requires a
 * non-null ATR even to detect BREACHED, so this watcher passes {@link BigDecimal#ZERO} in its
 * place: a zero-width band still classifies price&nbsp;&le;&nbsp;stop as BREACHED (matching
 * {@code StopZoneEvaluatorTest#nonPositiveAtrCollapsesProximityBand}), while the earlier
 * PROXIMITY warning stays unavailable until ATR is added to the context model. */
@Component
@ConditionalOnProperty(value = "dracul.stopguard.enabled", havingValue = "true")
public class StopProximityWatcher {

    private static final Logger log = LoggerFactory.getLogger(StopProximityWatcher.class);

    private final HeldPositionService heldPositions;
    private final AgoraMarketData marketData;
    private final StopAlertEmitter emitter;
    private final double atrMultiple;
    private final String connection;
    private final String owner;

    public StopProximityWatcher(HeldPositionService heldPositions, AgoraMarketData marketData,
            StopAlertEmitter emitter,
            @Value("${dracul.stopguard.atr-multiple:0.5}") double atrMultiple,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser) {
        this.heldPositions = heldPositions;
        this.marketData = marketData;
        this.emitter = emitter;
        this.atrMultiple = atrMultiple;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
    }

    @Scheduled(cron = "${dracul.stopguard.cron:0 */15 9-16 * * 1-5}", zone = "America/New_York")
    public void poll() {
        try {
            List<HeldPosition> withStop = heldPositions.openPositions(connection).stream()
                    .filter(p -> p.activeStop() != null)
                    .toList();
            if (withStop.isEmpty()) return;

            Set<String> symbols = new LinkedHashSet<>();
            for (HeldPosition p : withStop) symbols.add(p.symbol());

            Map<String, Quote> quotes;
            // AgoraMarketData.quotes() returns an empty map on Agora failure (never throws); this catch is defensive.
            try {
                quotes = marketData.quotes(symbols);
            } catch (Exception e) {
                log.warn("stopguard: price fetch failed for {} symbol(s) — skipping tick: {}",
                        symbols.size(), e.getMessage());
                return;
            }

            Instant now = Instant.now();
            for (HeldPosition p : withStop) {
                Quote q = quotes.get(p.symbol());
                if (q == null || q.price() == null) continue;
                StopZone zone = StopZoneEvaluator.evaluate(
                        q.price(), p.activeStop(), BigDecimal.ZERO, atrMultiple);
                if (zone == StopZone.NONE) continue;
                // Depot-sourced alerts carry no watchlist row -- identity and dedup are
                // keyed by (owner, symbol, trigger_type), not by the linked verdict, so a
                // missing verdictId is no longer a reason to skip (a verdict UUID is never
                // a valid watchlist_items(id) anyway; see StopAlertEmitter).
                BigDecimal stop = p.activeStop();
                emitter.emit(owner, p.symbol(), zone, q.price(), stop, now);
            }
        } catch (RuntimeException e) {
            log.warn("stopguard poll failed", e);
        }
    }
}
