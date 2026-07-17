package de.visterion.dracul.executor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Code-enforced rejection reasons. Slice 1 wired SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS.
 * The full 16-veto catalog (Task 5 + the CURRENCY_MISMATCH currency guard) adds the entry-completeness vetos plus the DATA_UNAVAILABLE
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
    CURRENCY_MISMATCH;

    /**
     * Transient = temporäre Raten-/Kapazitätsdeckel. Ein so abgelehntes Signal ist
     * aufgeschoben, nicht disqualifiziert: es bleibt PENDING und wird im nächsten
     * Executor-Lauf erneut geprüft, sobald wieder ein Slot frei ist. Alle übrigen
     * Gründe sind terminal (das Signal ist strukturell nicht handelbar).
     * Wie lange ein Signal aufgeschoben bleibt, ist für alle fünf transienten Gründe
     * gleich hart begrenzt: SIGNAL_EXPIRED steht im Veto-Katalog auf Position #3
     * (nach LOW_CONFIDENCE, VOR den transienten Deckeln COOLDOWN/MAX_POSITIONS/
     * BUDGET/HEAT_LIMIT und ohnehin vor PACE_LIMIT). Ein zu altes Signal bekommt
     * damit SIGNAL_EXPIRED als firstFailure — terminal — sobald es maxSignalAgeDays
     * (Default 5 Handelstage) überschreitet, egal welcher Deckel sonst noch greift.
     * Innerhalb des Fensters bleibt es bei einem transienten Veto PENDING und wird
     * nachgeholt; danach kippt es terminal REJECTED, statt ewig PENDING zu liegen.
     * (Reorder 2026-07-17: SIGNAL_EXPIRED von #12 auf #3 gezogen, damit die
     * 5-Handelstage-Grenze für alle fünf transienten Gründe gilt, nicht nur PACE_LIMIT.)
     */
    private static final Set<RejectReason> TRANSIENT = EnumSet.of(
            PACE_LIMIT, MAX_POSITIONS, BUDGET, HEAT_LIMIT, COOLDOWN);

    public boolean isTransient() {
        return TRANSIENT.contains(this);
    }
}
