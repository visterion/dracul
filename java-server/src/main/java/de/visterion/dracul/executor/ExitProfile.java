package de.visterion.dracul.executor;

import java.util.Locale;

/**
 * How an open position is exited (spec 2026-10-03 §5). {@link #STANDARD} is the executor's
 * classic lifecycle: chandelier trail, giveback, structured kill level, LLM soft exits and
 * tranche 2. {@link #CONVICTION} is the strigoi-tech basket profile: emergency stop below entry,
 * half sold once a close reaches the target, the rest trailed below the highest close, and a
 * nightly catastrophe check — only code exits it.
 *
 * <p>The profile is DERIVED from the signal mechanism, never carried as a separate prey/signal
 * column (R1 Minor 7): {@code TECH_CONVICTION} ⇒ CONVICTION, anything else (and no mechanism) ⇒
 * STANDARD. Matching is trimmed and case-insensitive, the normalisation
 * {@link EntryContextAssembler} already applies to mechanisms (R2 Minor 12).
 */
public enum ExitProfile {
    STANDARD,
    CONVICTION;

    /** The one mechanism that maps to {@link #CONVICTION}. */
    public static final String TECH_CONVICTION = "TECH_CONVICTION";

    public static ExitProfile fromMechanism(String mechanism) {
        if (mechanism == null) return STANDARD;
        return TECH_CONVICTION.equals(mechanism.trim().toUpperCase(Locale.ROOT))
                ? CONVICTION : STANDARD;
    }
}
