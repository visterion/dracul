package de.visterion.dracul.executor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Code-enforced rejection reasons. Slice 1 wired SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS.
 * The full 17-veto catalog (Task 5 + the CURRENCY_MISMATCH currency guard + the T3.3 PATTERN_GATE)
 * adds the entry-completeness vetos plus the DATA_UNAVAILABLE
 * pre-veto that short-circuits evaluation when {@code EntryContext.missing()} is non-empty, and
 * the BELOW_ANCHOR anchor guard that rejects entries on the invalidating side of the reference
 * price anchor.
 *
 * <p>NB: the constant declaration order below is historical and does NOT define veto precedence.
 * The veto-catalog order (which check runs first, and thus which becomes {@code firstFailure}) is
 * defined solely by the sequence of checks in {@code VetoService.evaluate}. Since the 2026-07-17
 * reorder, SIGNAL_EXPIRED runs at catalog #3 (ahead of the transient caps) even though it is still
 * declared 12th here. {@link #isTransient()} is an order-independent set membership, so neither the
 * enum ordinal nor this declaration order affects behavior.
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
    CURRENCY_MISMATCH,
    /** An operator-approved pattern gate (ACTIVE pattern + machine-checkable predicate,
     *  T3.3) matched this signal. Transient by design: with approve = enforce and no
     *  shadow mode, a mistranslated gate must be recoverable — the operator deactivates
     *  the pattern and the still-PENDING signals flow again, capped by SIGNAL_EXPIRED. */
    PATTERN_GATE;

    /**
     * Transient = temporary rate/capacity caps. A signal rejected for one of these
     * reasons is deferred, not disqualified: it stays PENDING and is re-evaluated on
     * the next executor run once a slot frees up. All other reasons are terminal (the
     * signal is structurally untradeable). How long a signal may stay deferred is
     * capped identically for all six transient reasons: SIGNAL_EXPIRED sits at
     * veto-catalog position #3 (after LOW_CONFIDENCE, BEFORE the transient caps
     * COOLDOWN/MAX_POSITIONS/BUDGET/HEAT_LIMIT/PATTERN_GATE and in any case before
     * PACE_LIMIT). A too-old signal therefore gets SIGNAL_EXPIRED as firstFailure —
     * terminal — once it exceeds maxSignalAgeDays (default 5 trading days), no matter
     * which cap would otherwise bite. Within the window a transiently vetoed signal
     * stays PENDING and is retried; afterwards it flips terminally to REJECTED instead
     * of lying PENDING forever. (Reorder 2026-07-17: SIGNAL_EXPIRED moved from #12 to
     * #3 so the 5-trading-day cap applies to all transient reasons, not just PACE_LIMIT.)
     */
    private static final Set<RejectReason> TRANSIENT = EnumSet.of(
            PACE_LIMIT, MAX_POSITIONS, BUDGET, HEAT_LIMIT, COOLDOWN, PATTERN_GATE);

    public boolean isTransient() {
        return TRANSIENT.contains(this);
    }
}
