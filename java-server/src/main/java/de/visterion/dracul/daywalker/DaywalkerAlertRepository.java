package de.visterion.dracul.daywalker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DaywalkerAlertRepository {

    private final JdbcClient jdbc;

    public DaywalkerAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolves a watchlist item id (UUID string) by ticker, or empty if unknown. */
    public Optional<String> resolveWatchlistItemId(String userId, String symbol) {
        var ids = jdbc.sql("""
                SELECT id FROM watchlist_items
                WHERE user_id = :u AND ticker = :t
                LIMIT 1
                """)
                .param("u", userId).param("t", symbol)
                .query(String.class).list();
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Most recent alert time for a (symbol, trigger_type), used for cooldown. */
    public Optional<Instant> lastAlertAt(String userId, String symbol, String triggerType) {
        var rows = jdbc.sql("""
                SELECT created_at FROM daywalker_alerts
                WHERE user_id = :u AND symbol = :s AND trigger_type = :tt
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("u", userId).param("s", symbol).param("tt", triggerType)
                .query((rs, n) -> rs.getTimestamp("created_at").toInstant())
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(String userId, String watchlistItemId, String symbol, String triggerType,
                       String severity, String thesis, BigDecimal confidence, String runId) {
        // Map the precise severity onto the frontend's WatchlistAlert level vocabulary
        // ('elevated' | 'info' | 'neutral'); the exact severity is preserved in the
        // `severity` column for downstream (Telegram / SSE) consumers.
        String level = switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL", "WARNING" -> "elevated";
            default -> "info";
        };
        java.util.UUID wid;
        try {
            wid = java.util.UUID.fromString(watchlistItemId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "DaywalkerAlertRepository.insert: invalid watchlistItemId '" + watchlistItemId + "'", e);
        }
        String nowIso = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO daywalker_alerts
                  (id, watchlist_item_id, at, message, level, user_id,
                   symbol, trigger_type, thesis, confidence, severity, vistierie_run_id)
                VALUES
                  (:id, :wid, :at, :msg, :lvl, :u,
                   :sym, :tt, :th, :conf, :sev, :run)
                """)
                .param("id", UUID.randomUUID())
                .param("wid", wid)
                .param("at", nowIso)
                .param("msg", thesis == null || thesis.isBlank() ? triggerType : thesis)
                .param("lvl", level)
                .param("u", userId)
                .param("sym", symbol)
                .param("tt", triggerType)
                .param("th", thesis)
                .param("conf", confidence)
                .param("sev", severity)
                .param("run", runId)
                .update();
    }
}
