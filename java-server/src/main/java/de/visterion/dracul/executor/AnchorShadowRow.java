package de.visterion.dracul.executor;

/** A recently-emitted signal with an emission anchor, sampled to shadow-check reconstruction. */
public record AnchorShadowRow(String signalId, String symbol, java.time.Instant emittedAt,
        java.time.LocalDate storedBarDate, java.math.BigDecimal storedAtr) {
}
