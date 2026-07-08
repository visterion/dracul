package de.visterion.dracul.executor;

/** One veto check outcome for the audit trace. */
public record VetoResult(String check, boolean passed) {}
