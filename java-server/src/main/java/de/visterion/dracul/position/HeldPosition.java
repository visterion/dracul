package de.visterion.dracul.position;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * The read model every position-agent consumes: a live depot position (from
 * {@code AgoraDepotClient}) left-joined by symbol to its OPEN {@code position_context} row.
 * The context block is nullable as a group -- a depot position with no open context row is
 * TA-only (no research context yet) and is never dropped from {@link HeldPositionService}'s
 * result.
 */
public record HeldPosition(
        String symbol,
        BigDecimal quantity,
        BigDecimal avgPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        String currency,
        String verdictId,
        JsonNode killCriteria,
        String horizon,
        JsonNode thesisSnapshot,
        BigDecimal initialStop,
        BigDecimal activeStop,
        String contextSource,
        String openedAt) {
}
