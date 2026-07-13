package de.visterion.dracul.gropar;

import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.watchlist.WatchlistChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>"Held" is now the live depot ⨝ context read model (see {@link HeldPositionService}),
 * not the watchlist -- depot-1 is the single source of truth for what's held.
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

    private final HeldPositionService heldPositionService;
    private final VistierieClient vistierie;
    private final String connection;

    /** Last pause state we successfully applied; null until the first apply. */
    private final AtomicReference<Boolean> lastApplied = new AtomicReference<>(null);

    public GroparPauseReconciler(HeldPositionService heldPositionService, VistierieClient vistierie,
            @Value("${dracul.position.connection:depot-1}") String connection) {
        this.heldPositionService = heldPositionService;
        this.vistierie = vistierie;
        this.connection = connection;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcile();
    }

    @EventListener(WatchlistChangedEvent.class)
    public void onWatchlistChanged(WatchlistChangedEvent e) {
        reconcile();
    }

    /** Recompute desired pause state from open depot positions and apply it if it changed. */
    public void reconcile() {
        boolean desiredPaused = heldPositionService.openPositions(connection).isEmpty();
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
