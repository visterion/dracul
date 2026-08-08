package de.visterion.dracul.renfield;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of {@code trade_proposals}, as read back by {@link TradeProposalRepository#findRecent}. */
public record TradeProposal(
        String id,
        String symbol,
        String action,
        String entryZone,
        String stop,
        BigDecimal confidence,
        String rationale,
        String marketNote,
        String runId,
        Instant createdAt,
        JsonNode newsSentiment) {
}
