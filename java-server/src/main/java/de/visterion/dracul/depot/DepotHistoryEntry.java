package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** One row of the Depot history tab. Facts are broker-authoritative; {@code why} is Dracul's
 *  optional, non-authoritative rationale annotation (present only when linkable by broker order id). */
public record DepotHistoryEntry(
        String source,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal profitLoss,
        String status,
        String brokerOrderId,
        String openedAt,
        String closedAt,
        BigDecimal avgFillPrice,
        boolean brokerConfirmed,
        Why why) {

    /** Dracul's rationale annotation — explicitly NOT authoritative for execution facts.
     *  {@code runId} anchors the raw Vistierie transcript (Schicht 2); null when not linkable. */
    public record Why(String strigoi, java.util.List<String> killCriteria, String entryReasoning,
            String draculExitReason, BigDecimal draculRealizedR, String runId) {
    }
}
