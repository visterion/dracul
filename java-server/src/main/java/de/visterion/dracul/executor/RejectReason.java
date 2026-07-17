package de.visterion.dracul.executor;

/**
 * Code-enforced rejection reasons. Slice 1 wired SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS.
 * The full 16-veto catalog (Task 5 + the CURRENCY_MISMATCH currency guard) adds the entry-completeness vetos plus the DATA_UNAVAILABLE
 * pre-veto that short-circuits evaluation when {@code EntryContext.missing()} is non-empty, and
 * the BELOW_ANCHOR anchor guard that rejects entries on the invalidating side of the reference
 * price anchor.
 */
public enum RejectReason {
    DATA_UNAVAILABLE,
    SCHEMA_INVALID,
    LOW_CONFIDENCE,
    COOLDOWN,
    MAX_POSITIONS,
    BUDGET,
    HEAT_LIMIT,
    CONCENTRATION,
    CONTRADICTION,
    REDUNDANCY,
    LIQUIDITY,
    SIGNAL_EXPIRED,
    CHASED_AWAY,
    BELOW_ANCHOR,
    PACE_LIMIT,
    NO_STOP,
    MAX_TRANCHE,
    TRANCHE_TOO_SMALL,
    NON_SIM_CONNECTION,
    DUPLICATE,
    NO_POSITION,
    NOT_ELIGIBLE,
    CORRELATED,
    UNKNOWN_VERSION,
    /** Instrument trades in a currency other than the configured single account/instrument
     *  currency (or the quote carried no currency). The executor is single-currency in this
     *  slice — a bracket order sized in the wrong currency would be silently mis-sized — so a
     *  non-account-currency find is surfaced + watchlisted + given a Verdict, but never entered. */
    CURRENCY_MISMATCH;

    /**
     * Transient = temporäre Raten-/Kapazitätsdeckel. Ein so abgelehntes Signal ist
     * aufgeschoben, nicht disqualifiziert: es bleibt PENDING und wird im nächsten
     * Executor-Lauf erneut geprüft, sobald wieder ein Slot frei ist. Alle übrigen
     * Gründe sind terminal (das Signal ist strukturell nicht handelbar). Die natürliche
     * Obergrenze eines aufgeschobenen Signals ist SIGNAL_EXPIRED (maxSignalAgeDays).
     */
    private static final java.util.Set<RejectReason> TRANSIENT = java.util.EnumSet.of(
            PACE_LIMIT, MAX_POSITIONS, BUDGET, HEAT_LIMIT, COOLDOWN);

    public boolean isTransient() {
        return TRANSIENT.contains(this);
    }
}
