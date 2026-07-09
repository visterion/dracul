package de.visterion.dracul.executor;

/** A temporary re-entry block on a symbol (e.g. after a stop-out). */
public record Cooldown(
        Long id,
        String symbol,
        String reason,
        String expiresAt,
        String exceptionCondition,
        String createdAt) {
}
