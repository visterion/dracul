package de.visterion.dracul.executor;

/**
 * One veto check outcome for the audit trace. {@code measured} is a mandatory human-readable
 * "actual value vs threshold" string (e.g. {@code "0.62 < 0.65"}) so a rejected/accepted decision
 * is auditable without re-deriving the underlying comparison.
 */
public record VetoResult(String check, boolean passed, String measured) {}
