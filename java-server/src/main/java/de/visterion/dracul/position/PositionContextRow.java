package de.visterion.dracul.position;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Full {@code position_context} row projection, as read back from the database. */
public record PositionContextRow(
        String id,
        String connection,
        String symbol,
        String verdictId,
        JsonNode killCriteria,
        String horizon,
        JsonNode thesisSnapshot,
        BigDecimal initialStop,
        BigDecimal activeStop,
        String openedAt,
        String closedAt,
        String source) {
}
