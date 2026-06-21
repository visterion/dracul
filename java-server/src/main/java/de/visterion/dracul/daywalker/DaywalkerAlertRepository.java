package de.visterion.dracul.daywalker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DaywalkerAlertRepository {

    private final JdbcClient jdbc;

    public DaywalkerAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A watchlist item's owner and id, for fanning a symbol's alert out to every owner. */
    public record OwnerItem(String userId, String watchlistItemId) {}

    /** All (owner, watchlist-item-id) pairs that hold a ticker, across all users. */
    public List<OwnerItem> findOwnersBySymbol(String symbol) {
        return jdbc.sql("""
                SELECT user_id, id FROM watchlist_items
                WHERE ticker = :t
                """)
                .param("t", symbol)
                .query((rs, n) -> new OwnerItem(rs.getString("user_id"), rs.getString("id")))
                .list();
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

    /** Back-compat: insert without an explicit notification outcome (not notified). */
    public void insert(String userId, String watchlistItemId, String symbol, String triggerType,
                       String severity, String thesis, BigDecimal confidence, String runId) {
        insert(userId, watchlistItemId, symbol, triggerType, severity, thesis, confidence, runId, false);
    }

    public void insert(String userId, String watchlistItemId, String symbol, String triggerType,
                       String severity, String thesis, BigDecimal confidence, String runId,
                       boolean notificationSent) {
        // Map the precise severity onto the frontend's WatchlistAlert level vocabulary
        // ('elevated' | 'info' | 'neutral'); the exact severity is preserved in the
        // `severity` column for downstream (Telegram / SSE) consumers.
        String level = switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL", "WARNING" -> "elevated";
            default -> "info";
        };
        UUID wid;
        try {
            wid = UUID.fromString(watchlistItemId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "DaywalkerAlertRepository.insert: invalid watchlistItemId '" + watchlistItemId + "'", e);
        }
        String nowIso = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO daywalker_alerts
                  (id, watchlist_item_id, at, message, level, user_id,
                   symbol, trigger_type, thesis, confidence, severity, vistierie_run_id, notification_sent)
                VALUES
                  (:id, :wid, :at, :msg, :lvl, :u,
                   :sym, :tt, :th, :conf, :sev, :run, :notified)
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
                .param("notified", notificationSent)
                .update();
    }
}
