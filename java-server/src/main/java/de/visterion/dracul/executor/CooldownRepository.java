package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Persists temporary re-entry blocks on symbols (e.g. after a stop-out). */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class CooldownRepository {

    private final JdbcClient jdbc;

    public CooldownRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String symbol, String reason, Instant expiresAt, String exceptionCondition) {
        jdbc.sql("""
                INSERT INTO cooldown (symbol, reason, expires_at, exception_condition)
                VALUES (:symbol, :reason, :expiresAt, :exceptionCondition)
                """)
                .param("symbol", symbol)
                .param("reason", reason)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("exceptionCondition", exceptionCondition)
                .update();
    }

    public List<Cooldown> active(Instant now) {
        return jdbc.sql("""
                SELECT * FROM cooldown WHERE expires_at > :now
                ORDER BY expires_at ASC
                """)
                .param("now", Timestamp.from(now))
                .query(this::mapRow)
                .list();
    }

    private Cooldown mapRow(ResultSet rs, int n) throws SQLException {
        Object expiresAtObj = rs.getObject("expires_at");
        Object createdAtObj = rs.getObject("created_at");
        return new Cooldown(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("reason"),
                expiresAtObj == null ? null : expiresAtObj.toString(),
                rs.getString("exception_condition"),
                createdAtObj == null ? null : createdAtObj.toString());
    }
}
