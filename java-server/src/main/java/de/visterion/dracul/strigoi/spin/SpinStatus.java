package de.visterion.dracul.strigoi.spin;

/**
 * Lifecycle state of a tracked spin-off candidate. Persisted as plain TEXT in
 * {@code spin_candidate.status} (see V26); this enum is the sole validator.
 *
 * <p>Forward-only progression:
 * <pre>
 *   REGISTERED ─▶ WHEN_ISSUED ─▶ DISTRIBUTED ─▶ SETTLED
 *        └────────────┴────────▶ ABANDONED (terminal, kept for audit)
 * </pre>
 * {@link #SETTLED} and {@link #ABANDONED} are terminal. There are no reverse
 * transitions; all status UPDATEs are guarded compare-and-set (WHERE status = from).
 */
public enum SpinStatus {
    REGISTERED,
    WHEN_ISSUED,
    DISTRIBUTED,
    SETTLED,
    ABANDONED;

    /** Terminal states are never reconciled further. */
    public boolean isTerminal() {
        return this == SETTLED || this == ABANDONED;
    }
}
