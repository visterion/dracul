package de.visterion.dracul.executor;

/**
 * One veto check outcome for the audit trace. {@code measured} is a mandatory human-readable
 * "actual value vs threshold" string (e.g. {@code "0.62 < 0.65"}) so a rejected/accepted decision
 * is auditable without re-deriving the underlying comparison.
 *
 * <p>{@code skipped} is non-null (always {@code "profile"}) when the signal's exit profile exempts
 * the check (spec 2026-10-03 §5.3). A skipped check is {@code passed = true} so it can never be
 * the first failure; the catalog keeps one entry per check for every profile.
 */
public record VetoResult(String check, boolean passed, String measured, String skipped) {

    public VetoResult(String check, boolean passed, String measured) {
        this(check, passed, measured, null);
    }

    /** A check exit profile CONVICTION does not run. */
    public static VetoResult skipped(String check, String measured) {
        return new VetoResult(check, true, measured, "profile");
    }
}
