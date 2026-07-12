package de.visterion.dracul.strigoi.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, cheap index-reconstitution lifecycle reconciliation. Modelled on
 * {@link de.visterion.dracul.strigoi.spin.SpinLifecycleReconciler} — recompute the desired state
 * from the persisted rows, then apply the forward-only status delta via guarded compare-and-set —
 * but strictly SIMPLER: it is PURE CALENDAR with ZERO Agora calls. The effective date is already
 * authoritative on every row (S&P press releases / Russell schedule carry it), so there is no
 * quote probe like the spin reconciler needs to detect the distribution event.
 *
 * <p>{@link #reconcile(LocalDate)} scans the non-terminal work queue oldest-checked first and
 * applies at most ONE transition per row per pass (mirroring the spin calendar phase — EFFECTIVE is
 * a transient tick that lasts at most one reconcile cycle before advancing to POST):
 * <ul>
 *   <li><b>ANNOUNCED &rarr; ABANDONED</b> (safety-valve, checked first): the announcement has sat
 *       {@code abandon-after-days} past its {@code announcement_date} while the {@code effective_date}
 *       is STILL in the future. That combination is a source/data anomaly — the normal path is
 *       ANNOUNCED&rarr;EFFECTIVE on the effective day. There is deliberately NO cancellation-signal
 *       logic in v1 (a genuine index removal is a distinct ADD/REMOVE change row, not a cancellation
 *       of this one).</li>
 *   <li><b>ANNOUNCED &rarr; EFFECTIVE</b>: {@code today >= effective_date} (stamps
 *       {@code effective_at}).</li>
 *   <li><b>EFFECTIVE &rarr; POST</b>: unconditional on the next pass (EFFECTIVE is transient).</li>
 *   <li><b>POST &rarr; CLOSED</b>: {@code today >= effective_date + observation-window-days}
 *       (terminal — the run-up/reversal observation window has elapsed).</li>
 * </ul>
 *
 * <p>Every UPDATE is the repository's guarded CAS ({@code WHERE status = from}), so a
 * duplicate/concurrent reconcile is a no-op and transitions never reverse. Fail-soft PER ROW (parity
 * with the spin reconciler fix): a {@code DataAccessException} or parse failure on one row degrades
 * it to "unchanged this run" and never kills the whole reconcile + hunt. Every visited row has its
 * {@code last_checked_at} bumped — implicitly by {@code advanceStatus} when it moves, explicitly by
 * {@code touchLastChecked} when it does not.
 */
@Component
public class IndexLifecycleReconciler {

    private static final Logger log = LoggerFactory.getLogger(IndexLifecycleReconciler.class);

    /** Index reconstitutions are infrequent; a generous scan bound covers every tracked
     *  non-terminal row cheaply (parity with the spin reconciler). */
    private static final int SCAN_LIMIT = 1000;

    private final IndexEventRepository repo;
    private final int observationWindowDays;
    private final int abandonAfterDays;

    public IndexLifecycleReconciler(
            IndexEventRepository repo,
            @Value("${dracul.strigoi.index.observation-window-days:30}") int observationWindowDays,
            @Value("${dracul.strigoi.index.abandon-after-days:45}") int abandonAfterDays) {
        this.repo = repo;
        this.observationWindowDays = observationWindowDays;
        this.abandonAfterDays = abandonAfterDays;
    }

    /** Outcome of one reconcile pass: the ids that moved, so the enrichment phase can prioritise
     *  freshly-transitioned rows over routine re-checks (mirrors the spin ReconcileResult). */
    public record ReconcileResult(Set<Long> transitionedIds) {
        public static ReconcileResult empty() { return new ReconcileResult(Set.of()); }
    }

    public ReconcileResult reconcile() {
        return reconcile(LocalDate.now());
    }

    /** Package-private date seam for deterministic tests. */
    ReconcileResult reconcile(LocalDate today) {
        List<IndexEventRow> rows = repo.findNonTerminalOldestCheckedFirst(SCAN_LIMIT);
        Set<Long> transitioned = new LinkedHashSet<>();

        for (IndexEventRow row : rows) {
            try {
                if (applyCalendar(row, today)) {
                    transitioned.add(row.id());
                } else {
                    // Checked, nothing moved — record the visit so the work queue rotates fairly.
                    repo.touchLastChecked(row.id());
                }
            } catch (RuntimeException e) {
                // Per-row fail-soft: a DB/parse failure on one row degrades it to "unchanged this
                // run", never kills the whole reconcile + hunt (spin-reconciler parity).
                log.debug("index reconcile: transition failed for row {}: {}", row.id(), e.getMessage());
            }
        }

        if (!transitioned.isEmpty()) {
            log.info("index reconcile: {} rows transitioned", transitioned.size());
        }
        return new ReconcileResult(transitioned);
    }

    /**
     * Applies at most one forward-only calendar transition for one row via the guarded CAS.
     * Returns whether the row moved this pass. Non-calendar / terminal states never move here.
     */
    private boolean applyCalendar(IndexEventRow row, LocalDate today) {
        return switch (row.status()) {
            case ANNOUNCED -> reconcileAnnounced(row, today);
            case EFFECTIVE ->
                    // Transient tick: advance to the observation window on the next pass,
                    // unconditionally.
                    repo.advanceStatus(row.id(), IndexEventStatus.EFFECTIVE, IndexEventStatus.POST);
            case POST -> {
                LocalDate eff = row.effectiveDate();
                if (eff != null && !today.isBefore(eff.plusDays(observationWindowDays))) {
                    yield repo.advanceStatus(row.id(), IndexEventStatus.POST, IndexEventStatus.CLOSED);
                }
                yield false;
            }
            default -> false; // CLOSED/ABANDONED are terminal and never reach the work queue
        };
    }

    /** ANNOUNCED-row transitions: the ABANDONED safety-valve is evaluated before the normal
     *  ANNOUNCED&rarr;EFFECTIVE promotion so a stuck source anomaly retires rather than flips
     *  effective on a stale row. */
    private boolean reconcileAnnounced(IndexEventRow row, LocalDate today) {
        LocalDate announcement = row.announcementDate();
        LocalDate effective = row.effectiveDate();

        // Safety-valve: announced long ago, effective still in the future -> source anomaly.
        if (announcement != null && effective != null
                && announcement.plusDays(abandonAfterDays).isBefore(today)
                && effective.isAfter(today)) {
            return repo.advanceStatus(row.id(), IndexEventStatus.ANNOUNCED, IndexEventStatus.ABANDONED);
        }

        // Normal path: the effective day has arrived.
        if (effective != null && !today.isBefore(effective)) {
            return repo.advanceStatus(row.id(), IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        }
        return false;
    }
}
