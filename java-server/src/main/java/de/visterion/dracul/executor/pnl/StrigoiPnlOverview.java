package de.visterion.dracul.executor.pnl;

import java.util.List;

/** {@code GET /api/executor/pnl/strigoi}: one row per strigoi. {@code currency} is always
 *  {@code EUR}; {@code fxBasis} {@code "current"} = converted at today's rate, not the trade date's. */
public record StrigoiPnlOverview(String connection, String currency, String fxBasis,
        List<StrigoiPnlSummary> strigoi) {
}
