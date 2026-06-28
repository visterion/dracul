package de.visterion.dracul.strigoi.echo;

/** Outcome of the deterministic hard-skip gate. {@code reason} is null when kept. */
public record GateDecision(boolean skipped, String reason) {
    public static GateDecision keep() { return new GateDecision(false, null); }
    public static GateDecision skip(String reason) { return new GateDecision(true, reason); }
}
