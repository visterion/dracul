package de.visterion.dracul.executor.pnl;

import java.util.List;

/** {@code GET /api/executor/pnl/strigoi/{name}}: the summary plus the trades, OPEN first, then
 *  CLOSED by close date descending. An unknown name is an empty detail, not a 404. */
public record StrigoiPnlDetail(String connection, String currency, String fxBasis,
        StrigoiPnlSummary summary, List<PnlTrade> trades) {
}
