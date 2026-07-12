package de.visterion.dracul.strigoi.index;

/**
 * Lifecycle state of a tracked index-reconstitution event. Persisted as plain TEXT in
 * {@code index_event.status} (see V27); this enum is the sole validator.
 *
 * <p>Forward-only progression:
 * <pre>
 *   ANNOUNCED ─▶ EFFECTIVE ─▶ POST ─▶ CLOSED
 *       └──────────────────────────▶ ABANDONED (terminal, safety-valve only)
 * </pre>
 * {@link #ANNOUNCED} is the window-open, pre-effective state (the only tradeable one);
 * {@link #EFFECTIVE} is a transient calendar tick; {@link #POST} is the run-up/reversal
 * observation window. {@link #CLOSED} and {@link #ABANDONED} are terminal. There are no
 * reverse transitions; all status UPDATEs are guarded compare-and-set (WHERE status = from).
 */
public enum IndexEventStatus {
    ANNOUNCED,
    EFFECTIVE,
    POST,
    CLOSED,
    ABANDONED;

    /** Terminal states are never reconciled further. */
    public boolean isTerminal() {
        return this == CLOSED || this == ABANDONED;
    }
}
