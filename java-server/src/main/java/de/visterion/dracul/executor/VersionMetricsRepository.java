package de.visterion.dracul.executor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Read-only aggregate query over {@code outcome_log} joined back to {@code decision_log},
 * grouped by {@code (source_agent, agent_version, rule_version)} — feeds
 * {@link VersionMetricsService}'s insufficient-sample gate (Task 5 / item 23). Mirrors the
 * analytics idiom in {@code de.visterion.dracul.outcome.OutcomeLogRepository} (unconditional
 * bean, same as that repository, so the service can be wired regardless of
 * {@code dracul.executor.enabled}).
 */
@Repository
public class VersionMetricsRepository {

    private final JdbcClient jdbc;

    public VersionMetricsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per {@code (source_agent, agent_version, rule_version)} group of completed
     *  {@code TRADE} outcomes, joined to {@code decision_log} for the first/last decision
     *  timestamp of the group. {@code avg_return} is the mean {@code realized_r} (quantity-
     *  weighted R-multiple); {@code hit_rate} is the fraction of rows with {@code realized_r > 0},
     *  matching the "won" definition used by {@code OutcomeLogRepository.findExecutorBrierPoints}. */
    public List<Row> findGroupedByVersion() {
        return jdbc.sql("""
                SELECT o.source_agent AS agent, o.agent_version AS agent_version,
                       o.rule_version AS rule_version,
                       COUNT(*) AS decisions,
                       MIN(d.created_at) AS first_at,
                       MAX(d.created_at) AS last_at,
                       AVG(o.realized_r) AS avg_return,
                       SUM(CASE WHEN o.realized_r > 0 THEN 1 ELSE 0 END)::float / COUNT(*) AS hit_rate
                FROM outcome_log o
                JOIN decision_log d ON d.log_id::text = o.log_id_ref
                WHERE o.kind = 'TRADE' AND o.complete = true
                GROUP BY o.source_agent, o.agent_version, o.rule_version
                ORDER BY o.source_agent, MIN(d.created_at)
                """)
                .query(this::mapRow)
                .list();
    }

    private Row mapRow(ResultSet rs, int n) throws SQLException {
        BigDecimal avgReturn = rs.getBigDecimal("avg_return");
        Object hitRateObj = rs.getObject("hit_rate");
        return new Row(
                rs.getString("agent"),
                rs.getString("agent_version"),
                rs.getString("rule_version"),
                rs.getInt("decisions"),
                rs.getTimestamp("first_at").toInstant(),
                rs.getTimestamp("last_at").toInstant(),
                avgReturn == null ? null : avgReturn.doubleValue(),
                hitRateObj == null ? null : rs.getDouble("hit_rate"));
    }

    /** Raw aggregate row, before {@link VersionMetricsService} applies the insufficient-sample
     *  gate. */
    public record Row(String agent, String agentVersion, String ruleVersion, int decisions,
            Instant firstAt, Instant lastAt, Double avgReturn, Double hitRate) {
    }
}
