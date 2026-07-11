package de.visterion.dracul.outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Persists {@code outcome_log} rows. Upserts by {@code log_id_ref} (the decision_log row this
 * outcome/counterfactual traces back to), so re-runs of {@link OutcomeBatchJob} refine an
 * existing row instead of duplicating it.
 */
@Repository
public class OutcomeLogRepository {

    private static final Logger log = LoggerFactory.getLogger(OutcomeLogRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutcomeLogRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(OutcomeLogRow r) {
        jdbc.sql("""
                INSERT INTO outcome_log
                  (kind, log_id_ref, position_id, symbol, reason_code, filled, fill_price,
                   slippage_vs_limit, holding_days, mfe_r, mae_r, realized_r, exit_trigger,
                   exit_log_id, partial_exits, reentry_within_10d, roundtrip_under_5d,
                   hypothetical, hunter_label, source_agent, agent_version, rule_version,
                   complete, computed_at)
                VALUES (:kind, :logIdRef, :positionId, :symbol, :reasonCode, :filled, :fillPrice,
                        :slippageVsLimit, :holdingDays, :mfeR, :maeR, :realizedR, :exitTrigger,
                        :exitLogId, CAST(:partialExits AS jsonb), :reentryWithin10d,
                        :roundtripUnder5d, CAST(:hypothetical AS jsonb), :hunterLabel,
                        :sourceAgent, :agentVersion, :ruleVersion, :complete, now())
                ON CONFLICT (log_id_ref) DO UPDATE SET
                  kind = EXCLUDED.kind,
                  position_id = EXCLUDED.position_id,
                  symbol = EXCLUDED.symbol,
                  reason_code = EXCLUDED.reason_code,
                  filled = EXCLUDED.filled,
                  fill_price = EXCLUDED.fill_price,
                  slippage_vs_limit = EXCLUDED.slippage_vs_limit,
                  holding_days = EXCLUDED.holding_days,
                  mfe_r = EXCLUDED.mfe_r,
                  mae_r = EXCLUDED.mae_r,
                  realized_r = EXCLUDED.realized_r,
                  exit_trigger = EXCLUDED.exit_trigger,
                  exit_log_id = EXCLUDED.exit_log_id,
                  partial_exits = EXCLUDED.partial_exits,
                  reentry_within_10d = EXCLUDED.reentry_within_10d,
                  roundtrip_under_5d = EXCLUDED.roundtrip_under_5d,
                  hypothetical = EXCLUDED.hypothetical,
                  hunter_label = EXCLUDED.hunter_label,
                  source_agent = EXCLUDED.source_agent,
                  agent_version = EXCLUDED.agent_version,
                  rule_version = EXCLUDED.rule_version,
                  complete = EXCLUDED.complete,
                  computed_at = now()
                """)
                .param("kind", r.kind())
                .param("logIdRef", r.logIdRef())
                .param("positionId", r.positionId())
                .param("symbol", r.symbol())
                .param("reasonCode", r.reasonCode())
                .param("filled", r.filled())
                .param("fillPrice", r.fillPrice())
                .param("slippageVsLimit", r.slippageVsLimit())
                .param("holdingDays", r.holdingDays())
                .param("mfeR", r.mfeR())
                .param("maeR", r.maeR())
                .param("realizedR", r.realizedR())
                .param("exitTrigger", r.exitTrigger())
                .param("exitLogId", r.exitLogId())
                .param("partialExits", writeJson(r.partialExits(), "[]"))
                .param("reentryWithin10d", r.reentryWithin10d())
                .param("roundtripUnder5d", r.roundtripUnder5d())
                .param("hypothetical", writeJson(r.hypothetical(), "{}"))
                .param("hunterLabel", r.hunterLabel())
                .param("sourceAgent", r.sourceAgent())
                .param("agentVersion", r.agentVersion())
                .param("ruleVersion", r.ruleVersion())
                .param("complete", r.complete())
                .update();
    }

    /** True when a row already exists for {@code logIdRef} AND is marked complete — the batch
     *  job skips re-processing in that case (closed-position TRADE rows never change again;
     *  COUNTERFACTUAL rows stay incomplete until skipped or the 60-bar window is filled). */
    public boolean isComplete(String logIdRef) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT complete FROM outcome_log WHERE log_id_ref = :ref")
                .param("ref", logIdRef)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    /** Test/debug accessor: full row by log_id_ref, or null. */
    public OutcomeLogRow findByLogIdRef(String logIdRef) {
        return jdbc.sql("SELECT * FROM outcome_log WHERE log_id_ref = :ref")
                .param("ref", logIdRef)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    List<OutcomeLogRow> findAll() {
        return jdbc.sql("SELECT * FROM outcome_log ORDER BY computed_at ASC")
                .query(this::mapRow)
                .list();
    }

    private OutcomeLogRow mapRow(ResultSet rs, int n) throws SQLException {
        Object positionIdObj = rs.getObject("position_id");
        return new OutcomeLogRow(
                rs.getString("kind"),
                rs.getString("log_id_ref"),
                positionIdObj == null ? null : rs.getLong("position_id"),
                rs.getString("symbol"),
                rs.getString("reason_code"),
                (Boolean) rs.getObject("filled"),
                rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("slippage_vs_limit"),
                (Integer) rs.getObject("holding_days"),
                rs.getBigDecimal("mfe_r"),
                rs.getBigDecimal("mae_r"),
                rs.getBigDecimal("realized_r"),
                rs.getString("exit_trigger"),
                rs.getString("exit_log_id"),
                readJson(rs.getString("partial_exits")),
                (Boolean) rs.getObject("reentry_within_10d"),
                (Boolean) rs.getObject("roundtrip_under_5d"),
                readJson(rs.getString("hypothetical")),
                (Boolean) rs.getObject("hunter_label"),
                rs.getString("source_agent"),
                rs.getString("agent_version"),
                rs.getString("rule_version"),
                rs.getBoolean("complete"));
    }

    private String writeJson(JsonNode node, String fallback) {
        try { return mapper.writeValueAsString(node == null ? mapper.readTree(fallback) : node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize outcome-log JSON", e); }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return null;
        }
    }
}
