package de.visterion.dracul.verdict;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VerdictRepository {

    private static final Logger log = LoggerFactory.getLogger(VerdictRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public VerdictRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Verdict> findAllByUser(String userId) {
        return findAllByUser(userId, false);
    }

    public List<Verdict> findAllByUser(String userId, boolean includeDismissed) {
        String dismissClause = includeDismissed
                ? ""
                : " AND (decision IS NULL OR decision <> 'DISMISS')";
        return jdbc.sql("""
                SELECT id, symbol, company_name, contributing_strigoi,
                       consensus_score, summary, created_at
                FROM verdicts
                WHERE user_id = :userId
                """ + dismissClause + """
                ORDER BY created_at DESC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new Verdict(
                        rs.getString("id"),
                        rs.getString("symbol"),
                        rs.getString("company_name"),
                        readList(rs.getString("contributing_strigoi")),
                        rs.getDouble("consensus_score"),
                        rs.getString("summary"),
                        rs.getString("created_at")
                ))
                .list();
    }

    public Optional<VerdictDetail> findDetailById(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT id, symbol, company_name, contributing_strigoi, consensus_score,
                       summary, created_at, anomaly_types, current_price, avg_confidence,
                       horizon, signals, risks, contributing_details,
                       decision, decided_at, currency
                FROM verdicts
                WHERE id = :id
                """)
                .param("id", uuid)
                .query((rs, rowNum) -> new VerdictDetail(
                        rs.getString("id"),
                        rs.getString("symbol"),
                        rs.getString("company_name"),
                        readList(rs.getString("contributing_strigoi")),
                        rs.getDouble("consensus_score"),
                        rs.getString("summary"),
                        rs.getString("created_at"),
                        readList(rs.getString("anomaly_types")),
                        rs.getDouble("current_price"),
                        rs.getDouble("avg_confidence"),
                        rs.getString("horizon"),
                        readList(rs.getString("signals")),
                        readList(rs.getString("risks")),
                        readDetails(rs.getString("contributing_details")),
                        rs.getString("decision"),
                        rs.getString("decided_at"),
                        rs.getString("currency") == null ? "USD" : rs.getString("currency")
                ))
                .optional();
    }

    public Optional<String> findLatestCreatedAt(String userId) {
        return jdbc.sql("""
                SELECT created_at FROM verdicts
                WHERE user_id = :userId
                ORDER BY created_at DESC LIMIT 1
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> rs.getString("created_at"))
                .optional();
    }

    public Optional<String> updateDecision(String id, String decision) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
        int rows = jdbc.sql("""
                UPDATE verdicts
                   SET decision   = :decision,
                       decided_at = now()
                 WHERE id = :id
                """)
                .param("decision", decision)
                .param("id", uuid)
                .update();
        if (rows == 0) return Optional.empty();
        return jdbc.sql("SELECT decided_at::text FROM verdicts WHERE id = :id")
                .param("id", uuid)
                .query(String.class)
                .optional();
    }

    /**
     * The most-recent verdict for a symbol, used by the synthesizer's upsert decision.
     * MAY already carry a user {@code decision} (TRACK/INTERESTING/DISMISS/ACTED) — callers
     * must inspect {@link #decision()} before overwriting, to honor "user decision wins".
     */
    public record ActiveVerdict(String id, String decision, List<String> contributingPreyIds) {}

    /**
     * Returns the most-recent verdict for the symbol (whether or not the user has decided it),
     * or empty if none exists. Callers must check {@link ActiveVerdict#decision()} before
     * calling {@link #updateSynthesized}.
     */
    public Optional<ActiveVerdict> findActiveBySymbol(String symbol, String userId) {
        return jdbc.sql("""
                SELECT id, decision, contributing_prey_ids
                FROM verdicts
                WHERE symbol = :symbol AND user_id = :userId
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("symbol", symbol)
                .param("userId", userId)
                .query((rs, rowNum) -> new ActiveVerdict(
                        rs.getString("id"),
                        rs.getString("decision"),
                        readList(rs.getString("contributing_prey_ids"))))
                .optional();
    }

    /** Insert a synthesizer-produced verdict. Returns the new id. decision stays null. */
    public String insertSynthesized(
            String symbol, String companyName, List<String> contributingStrigoi,
            double consensusScore, String summary, List<String> anomalyTypes,
            BigDecimal currentPrice, String currency, double avgConfidence, String horizon,
            List<String> signals, List<String> risks,
            List<ContributingStrigoiDetail> contributingDetails,
            List<String> contributingPreyIds, String userId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO verdicts
                  (id, symbol, company_name, contributing_strigoi, consensus_score, summary,
                   created_at, anomaly_types, current_price, currency, avg_confidence, horizon,
                   signals, risks, contributing_details, user_id, contributing_prey_ids)
                VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, now(), ?::jsonb, ?, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb)
                """)
                .params(id, symbol, companyName, json(contributingStrigoi), consensusScore, summary,
                        json(anomalyTypes), currentPrice, currency, avgConfidence, horizon,
                        json(signals), json(risks), json(contributingDetails), userId,
                        json(contributingPreyIds))
                .update();
        return id;
    }

    /** Update a synthesizer-produced verdict's content. Leaves decision/decided_at/created_at. */
    public void updateSynthesized(
            String id, String companyName, List<String> contributingStrigoi,
            double consensusScore, String summary, List<String> anomalyTypes,
            BigDecimal currentPrice, String currency, double avgConfidence, String horizon,
            List<String> signals, List<String> risks,
            List<ContributingStrigoiDetail> contributingDetails,
            List<String> contributingPreyIds, String userId) {
        jdbc.sql("""
                UPDATE verdicts SET
                  company_name = ?, contributing_strigoi = ?::jsonb, consensus_score = ?,
                  summary = ?, anomaly_types = ?::jsonb, current_price = ?, currency = ?,
                  avg_confidence = ?, horizon = ?, signals = ?::jsonb, risks = ?::jsonb,
                  contributing_details = ?::jsonb, contributing_prey_ids = ?::jsonb
                WHERE id = ?::uuid
                """)
                .params(companyName, json(contributingStrigoi), consensusScore, summary,
                        json(anomalyTypes), currentPrice, currency, avgConfidence, horizon,
                        json(signals), json(risks), json(contributingDetails),
                        json(contributingPreyIds), id)
                .update();
    }

    public java.util.List<String> distinctCurrencies() {
        return jdbc.sql("SELECT DISTINCT currency FROM verdicts WHERE currency IS NOT NULL")
                .query(String.class)
                .list();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize verdict JSON", e);
        }
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

    private List<ContributingStrigoiDetail> readDetails(String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
