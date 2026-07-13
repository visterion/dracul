package de.visterion.dracul.gropar;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ExitSignalRepository {

    private static final Logger log = LoggerFactory.getLogger(ExitSignalRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExitSignalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return true if a new row was inserted, false if it was a duplicate (run, symbol) and skipped.
     *  The (run, symbol) arbiter (V29) is what actually fires for gropar's depot-sourced
     *  signals -- they never carry a watchlist_item_id, so the older (run, item) index
     *  (V21) never matches them. Both indexes stay in place: this insert simply targets
     *  the one that's guaranteed to cover every source. */
    public boolean insert(ExitSignal s, String userId) {
        UUID id = UUID.fromString(s.id());
        UUID itemId = s.watchlistItemId() != null ? UUID.fromString(s.watchlistItemId()) : null;
        int rows = jdbc.sql("""
                INSERT INTO exit_signals
                  (id, watchlist_item_id, symbol, action, fired_rules, gain_loss_pct,
                   thesis_status, rationale, confidence, vistierie_run_id, run_at, user_id)
                VALUES (:id, :item, :symbol, :action,
                        CAST(:rules AS jsonb), :gl, :thesis, :rationale, :conf, :run, :runAt, :user)
                ON CONFLICT (vistierie_run_id, symbol)
                  WHERE vistierie_run_id IS NOT NULL
                  DO NOTHING
                """)
                .param("id", id)
                .param("item", itemId)
                .param("symbol", s.symbol())
                .param("action", s.action())
                .param("rules", writeJson(s.firedRules()))
                .param("gl", s.gainLossPct())
                .param("thesis", s.thesisStatus())
                .param("rationale", s.rationale())
                .param("conf", s.confidence())
                .param("run", s.vistierieRunId())
                .param("runAt", s.runAt())
                .param("user", userId)
                .update();
        return rows > 0;
    }

    public List<ExitSignal> findLatestByUser(String userId, int limit) {
        return jdbc.sql("""
                SELECT * FROM exit_signals WHERE user_id = :user
                ORDER BY created_at DESC LIMIT :lim
                """)
                .param("user", userId).param("lim", limit)
                .query((rs, n) -> new ExitSignal(
                        rs.getString("id"),
                        rs.getString("watchlist_item_id"),
                        rs.getString("symbol"),
                        rs.getString("action"),
                        readList(rs.getString("fired_rules")),
                        rs.getObject("gain_loss_pct") == null ? null : rs.getDouble("gain_loss_pct"),
                        rs.getString("thesis_status"),
                        rs.getString("rationale"),
                        rs.getObject("confidence") == null ? null : rs.getDouble("confidence"),
                        rs.getString("vistierie_run_id"),
                        rs.getString("run_at")))
                .list();
    }

    private String writeJson(List<String> v) {
        try { return mapper.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize exit-signal firedRules", e); }
    }

    private List<String> readList(String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
