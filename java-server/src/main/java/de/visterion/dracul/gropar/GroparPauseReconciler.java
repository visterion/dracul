package de.visterion.dracul.gropar;

import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.watchlist.WatchlistChangedEvent;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the gropar exit agent paused while no held positions exist and unpauses it
 * when at least one exists. gropar only does useful work over held positions, so
 * pausing it (Vistierie skips a paused agent's cron) avoids burning empty runs.
 *
 * <p>gropar's pause is system-managed by this reconciler: the operator turns gropar
 * on/off via its {@code dracul.gropar.enabled} flag, not the manual pause toggle.
 * Only present when gropar is enabled — when disabled, gropar is not registered at
 * Vistierie at all.
 */
@Component
@Order(30) // after GenericAgentRegistrar (@Order 20) so gropar is registered first
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
public class GroparPauseReconciler {

    private static final Logger log = LoggerFactory.getLogger(GroparPauseReconciler.class);
    private static final String AGENT = "gropar";

    private final WatchlistRepository watchlistRepo;
    private final VistierieClient vistierie;

    /** Last pause state we successfully applied; null until the first apply. */
    private final AtomicReference<Boolean> lastApplied = new AtomicReference<>(null);

    public GroparPauseReconciler(WatchlistRepository watchlistRepo, VistierieClient vistierie) {
        this.watchlistRepo = watchlistRepo;
        this.vistierie = vistierie;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcile();
    }

    @EventListener(WatchlistChangedEvent.class)
    public void onWatchlistChanged(WatchlistChangedEvent e) {
        reconcile();
    }

    /** Recompute desired pause state from held count and apply it if it changed. */
    public void reconcile() {
        boolean desiredPaused = watchlistRepo.countHeldAll() == 0;
        if (Boolean.valueOf(desiredPaused).equals(lastApplied.get())) {
            return; // no change — avoid a redundant Vistierie call
        }
        try {
            vistierie.patchAgent(AGENT, desiredPaused);
            lastApplied.set(desiredPaused);
            log.info("gropar pause reconciled to {} (held positions {})",
                    desiredPaused, desiredPaused ? "= 0" : ">= 1");
        } catch (Exception ex) {
            // Do not advance lastApplied: the next watchlist event / restart retries.
            log.warn("gropar pause reconcile failed (paused={}): {}", desiredPaused, ex.getMessage());
        }
    }
}
