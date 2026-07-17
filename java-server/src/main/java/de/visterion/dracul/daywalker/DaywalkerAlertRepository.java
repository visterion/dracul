package de.visterion.dracul.daywalker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DaywalkerAlertRepository {

    private final JdbcClient jdbc;

    public DaywalkerAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A watchlist item's owner, id, and whether it is a HELD position (for routing). */
    public record OwnerItem(String userId, String watchlistItemId, boolean held) {}

    /** All (owner, watchlist-item-id, held) triples that hold a ticker, across all users. */
    public List<OwnerItem> findOwnersBySymbol(String symbol) {
        return jdbc.sql("""
                SELECT user_id, id, tag FROM watchlist_items
                WHERE ticker = :t
                """)
                .param("t", symbol)
                .query((rs, n) -> new OwnerItem(
                        rs.getString("user_id"), rs.getString("id"),
                        "HELD".equals(rs.getString("tag"))))
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
        insert(userId, watchlistItemId, symbol, triggerType, severity, thesis, confidence,
                runId, notificationSent, null);
    }

    public void insert(String userId, String watchlistItemId, String symbol, String triggerType,
                       String severity, String thesis, BigDecimal confidence, String runId,
                       boolean notificationSent, String eventType) {
        // Map the precise severity onto the frontend's WatchlistAlert level vocabulary
        // ('elevated' | 'info' | 'neutral'); the exact severity is preserved in the
        // `severity` column for downstream (Telegram / SSE) consumers.
        String level = levelFor(severity);
        // Depot-sourced alerts (A6) carry no watchlist row at all -- watchlistItemId is null
        // in that case (V30 made the column nullable) and the row is keyed by symbol instead.
        UUID wid = null;
        if (watchlistItemId != null) {
            try {
                wid = UUID.fromString(watchlistItemId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "DaywalkerAlertRepository.insert: invalid watchlistItemId '" + watchlistItemId + "'", e);
            }
        }
        String nowIso = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO daywalker_alerts
                  (id, watchlist_item_id, at, message, level, user_id,
                   symbol, trigger_type, thesis, confidence, severity, vistierie_run_id,
                   notification_sent, event_type)
                VALUES
                  (:id, :wid, :at, :msg, :lvl, :u,
                   :sym, :tt, :th, :conf, :sev, :run, :notified, :et)
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
                .param("et", eventType)
                .update();
    }

    /** An existing alert row for (owner, symbol, trigger_type) on one UTC day. */
    public record SameDayAlert(String id, String severity) {}

    /** Latest alert for (owner, symbol, trigger_type) created on the same UTC day as {@code now}. */
    public Optional<SameDayAlert> findSameUtcDay(String userId, String symbol, String triggerType, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var rows = jdbc.sql("""
                SELECT id, severity FROM daywalker_alerts
                WHERE user_id = :u AND symbol = :s AND trigger_type = :tt
                  AND created_at >= :from AND created_at < :to
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("u", userId).param("s", symbol).param("tt", triggerType)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new SameDayAlert(rs.getString("id"), rs.getString("severity")))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Refresh a same-day duplicate in place: new text/timestamps/run. Severity is
     *  passed by the caller, which escalates and never lowers it. */
    public void updateSameDayAlert(String id, String triggerType, String severity, String thesis,
                                   BigDecimal confidence, String runId, boolean notificationSent) {
        updateSameDayAlert(id, triggerType, severity, thesis, confidence, runId,
                notificationSent, null);
    }

    /** Widest form (T1.3): {@code eventType} uses keep-if-null semantics — a daywalker-deep
     *  escalation verdict (whose schema carries no event_type) must never null a value the
     *  original daywalker assessment persisted; COALESCE enforces that IN the SQL. */
    public void updateSameDayAlert(String id, String triggerType, String severity, String thesis,
                                   BigDecimal confidence, String runId, boolean notificationSent,
                                   String eventType) {
        jdbc.sql("""
                UPDATE daywalker_alerts
                   SET at = :at, message = :msg, level = :lvl, thesis = :th, confidence = :conf,
                       severity = :sev, vistierie_run_id = :run,
                       notification_sent = notification_sent OR :notified,
                       event_type = COALESCE(:et, event_type),
                       created_at = now()
                 WHERE id = :id
                """)
                .param("id", UUID.fromString(id))
                .param("at", Instant.now().toString())
                .param("msg", thesis == null || thesis.isBlank() ? triggerType : thesis)
                .param("lvl", levelFor(severity))
                .param("th", thesis)
                .param("conf", confidence)
                .param("sev", severity)
                .param("run", runId)
                .param("notified", notificationSent)
                .param("et", eventType)
                .update();
    }

    /** Frontend WatchlistAlert level vocabulary for a precise severity. */
    private static String levelFor(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL", "WARNING" -> "elevated";
            default -> "info";
        };
    }
}
