package de.visterion.dracul.position;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * The research context supplied when opening a paper position: which verdict backed the
 * entry, the kill criteria and horizon copied from that verdict, a snapshot of the thesis
 * at entry time, and the initial protective stop. Carries no id or timestamps -- those are
 * assigned on insert (see {@link PositionContextRepository#upsertOnOpen}) and read back via
 * {@link PositionContextRow}.
 */
public record PositionContext(
        String connection,
        String symbol,
        String verdictId,
        JsonNode killCriteria,
        String horizon,
        JsonNode thesisSnapshot,
        BigDecimal initialStop,
        String source) {
}
