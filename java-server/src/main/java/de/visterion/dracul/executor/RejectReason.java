package de.visterion.dracul.executor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Code-enforced rejection reasons. Slice 1 wired SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS.
 * The full 18-veto catalog (Task 5 + the CURRENCY_MISMATCH currency guard + the T3.3 PATTERN_GATE
 * + the SP2 MECHANISM_BUDGET cap) adds the entry-completeness vetos plus the DATA_UNAVAILABLE
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
    /** New-entry exposure in the signal's mechanism plus one tranche would exceed the mechanism's
     *  configured share of the total budget (SP2, veto 5b, entry cap only — add_tranche is not gated). */
    MECHANISM_BUDGET,
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
    /** The protective stop distance, converted to account currency, exceeds the entire per-trade
     *  risk budget ({@code total-budget x risk-pct}) — even one share would risk more than the
     *  budget allows. Code-enforced by {@code PositionSizer} and routed by
     *  {@code ExecutorWebhookController}, so it sits outside the {@code VetoService} catalog next
     *  to {@code TRANCHE_TOO_SMALL}.
     *
     *  <p>TERMINAL, not transient: nothing about waiting a run changes an instrument's stop
     *  distance, and a transient classification would leave the signal silently PENDING until
     *  SIGNAL_EXPIRED. A fresh signal with a tighter stop is a new signal. */
    RISK_TOO_WIDE,
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
     * Transient = temporary rate/capacity caps. {@code place_entry} leaves a signal rejected for one
     * of these reasons {@code PENDING} for the current run instead of {@code REJECTED}. What happens
     * next is NOT an in-executor retry: the LLM's {@code submit_decision} normally records a SKIP for
     * the same signal in the same run and that marks it {@code SKIPPED} (prod, 2026-09: every
     * MAX_POSITIONS decision ended SKIPPED within the run). The retry that exists today is the
     * producer's re-emission of the symbol on a later run (PreySignalEmitter suppresses only PENDING
     * symbols and open positions). Making transient vetoes defer inside the executor is the SP2b
     * slice. All other reasons are terminal. SIGNAL_EXPIRED sits at catalog #3, ahead of every
     * transient cap, so a too-old signal is REJECTED regardless of which cap would bite.
     */
    private static final Set<RejectReason> TRANSIENT = EnumSet.of(
            PACE_LIMIT, MAX_POSITIONS, MECHANISM_BUDGET, BUDGET, HEAT_LIMIT, COOLDOWN, PATTERN_GATE);

    public boolean isTransient() {
        return TRANSIENT.contains(this);
    }
}
