package de.visterion.dracul.vistierie;

public record KillStatus(
        String until,
        String reason,
        String setBy
) {
    public boolean active() { return until != null; }
}
