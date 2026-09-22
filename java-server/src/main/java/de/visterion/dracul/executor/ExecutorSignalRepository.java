package de.visterion.dracul.executor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Persists injected {@link ExecutorSignal}s awaiting execution evaluation. */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorSignalRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutorSignalRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ExecutorSignalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(ExecutorSignal s) {
        String status = s.status() != null ? s.status() : "PENDING";
        jdbc.sql("""
                INSERT INTO executor_signal
                  (signal_id, source, agent_version, symbol, direction, confidence, mechanism,
                   kill_criteria, horizon, reference_price, status, thesis, prey_id,
                   reference_bar_date, reference_atr, reference_source)
                VALUES (:signalId, :source, :agentVersion, :symbol, :direction, :confidence, :mechanism,
                        CAST(:killCriteria AS jsonb), :horizon, :referencePrice, :status,
                        CAST(:thesis AS jsonb), CAST(:preyId AS uuid),
                        :referenceBarDate, :referenceAtr, :referenceSource)
                ON CONFLICT (signal_id) DO NOTHING
                """)
                .param("signalId", s.signalId())
                .param("source", s.source())
                .param("agentVersion", s.agentVersion())
                .param("symbol", s.symbol())
                .param("direction", s.direction())
                .param("confidence", s.confidence())
                .param("mechanism", s.mechanism())
                .param("killCriteria", writeJson(s.killCriteria()))
                .param("horizon", s.horizon())
                .param("referencePrice", s.referencePrice())
                .param("status", status)
                .param("thesis", writeThesis(s.thesis()))
                .param("preyId", s.preyId())
                .param("referenceBarDate", s.referenceBarDate())
                .param("referenceAtr", s.referenceAtr())
                .param("referenceSource",
                        s.referenceBarDate() != null && s.referenceAtr() != null ? "emission" : null)
                .update();
    }

    public List<ExecutorSignal> findPending(int limit) {
        return jdbc.sql("""
                SELECT * FROM executor_signal WHERE status = 'PENDING'
                ORDER BY created_at ASC LIMIT :lim
                """)
                .param("lim", limit)
                .query(this::mapRow)
                .list();
    }

    public ExecutorSignal findById(String signalId) {
        return jdbc.sql("SELECT * FROM executor_signal WHERE signal_id = :signalId")
                .param("signalId", signalId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    /** Run-id of the prey linked to this signal (Schicht 1 FK executor_signal.prey_id -> prey.id).
     *  Null when the signal is unknown, has no prey_id (operator inject / legacy), or prey.run_id is null. */
    public String findRunIdBySignalId(String signalId) {
        return jdbc.sql("""
                SELECT p.run_id
                FROM executor_signal es
                JOIN prey p ON p.id = es.prey_id
                WHERE es.signal_id = :signalId
                """)
                .param("signalId", signalId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** Signals whose LLM SKIP / unevaluated sweep would be walked by OutcomeBatchJob if they had
     *  anchors — the exact populations of ExecutorDecisionRepository.findSkipsWithoutDecisionLog and
     *  findSweptWithoutDecisionLog with the anchor predicate inverted. created_at is the EMISSION
     *  instant, read zone-free; the decision's created_at would move 82 of 191 prod anchors. */
    public List<AnchorCandidate> findAnchorCandidates(int limit) {
        return jdbc.sql("""
                SELECT s.signal_id, s.symbol, s.created_at
                FROM executor_signal s
                WHERE (s.reference_bar_date IS NULL OR s.reference_atr IS NULL)
                  AND s.reference_source IS NULL
                  AND s.reference_price > 0
                  AND s.status <> 'ACCEPTED'
                  AND EXISTS (SELECT 1 FROM executor_decision d
                              WHERE d.signal_id = s.signal_id
                                AND ((d.action = 'SKIP' AND d.reject_reason IS NULL)
                                  OR (d.action = :sweepAction AND d.reject_reason = 'SIGNAL_EXPIRED')))
                  AND NOT EXISTS (SELECT 1 FROM decision_log l
                                  WHERE l.signal_id = s.signal_id
                                    AND l.trigger_type = 'SIGNAL' AND l.action = 'REJECT')
                ORDER BY s.created_at ASC, s.signal_id ASC
                LIMIT :limit
                """)
                .param("sweepAction", PendingSignalSweeper.ACTION)
                .param("limit", limit)
                .query((rs, n) -> new AnchorCandidate(
                        rs.getString("signal_id"), rs.getString("symbol"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    public List<AnchorShadowRow> findShadowSample(int limit, java.time.Instant emittedAfter) {
        return jdbc.sql("""
                SELECT signal_id, symbol, created_at, reference_bar_date, reference_atr
                FROM executor_signal
                WHERE reference_source = 'emission' AND created_at > :after
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("after", java.time.OffsetDateTime.ofInstant(emittedAfter, java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, n) -> new AnchorShadowRow(
                        rs.getString("signal_id"), rs.getString("symbol"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("reference_bar_date", java.time.LocalDate.class),
                        rs.getBigDecimal("reference_atr")))
                .list();
    }

    /** Never touches emission anchors; a half-anchored row gets BOTH values from one source. */
    public int writeReconstructedAnchor(String signalId, java.time.LocalDate barDate, BigDecimal atr) {
        return jdbc.sql("""
                UPDATE executor_signal
                   SET reference_bar_date = :d, reference_atr = :a, reference_source = 'reconstructed'
                 WHERE signal_id = :id AND reference_source IS NULL
                   AND (reference_bar_date IS NULL OR reference_atr IS NULL)
                """)
                .param("d", barDate).param("a", atr).param("id", signalId)
                .update();
    }

    public int markUnreconstructable(String signalId) {
        return jdbc.sql("""
                UPDATE executor_signal SET reference_source = 'unreconstructable'
                 WHERE signal_id = :id AND reference_source IS NULL
                """)
                .param("id", signalId)
                .update();
    }

    public String findReferenceSource(String signalId) {
        return jdbc.sql("SELECT reference_source FROM executor_signal WHERE signal_id = :id")
                .param("id", signalId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void markStatus(String signalId, String status) {
        jdbc.sql("""
                UPDATE executor_signal SET status = :status, processed_at = now()
                WHERE signal_id = :signalId
                """)
                .param("status", status)
                .param("signalId", signalId)
                .update();
    }

    private ExecutorSignal mapRow(ResultSet rs, int n) throws SQLException {
        Object confidenceObj = rs.getObject("confidence");
        BigDecimal referencePrice = rs.getBigDecimal("reference_price");
        Object createdAtObj = rs.getObject("created_at");
        return new ExecutorSignal(
                rs.getString("signal_id"),
                rs.getString("source"),
                rs.getString("agent_version"),
                rs.getString("symbol"),
                rs.getString("direction"),
                confidenceObj == null ? null : rs.getDouble("confidence"),
                rs.getString("mechanism"),
                readList(rs.getString("kill_criteria")),
                rs.getString("horizon"),
                referencePrice,
                rs.getString("status"),
                createdAtObj == null ? null : createdAtObj.toString(),
                readThesis(rs.getString("thesis")),
                rs.getString("prey_id"),
                // getObject(..., LocalDate.class), never a Timestamp: a Timestamp read would shift
                // the day in a non-UTC JVM, and this date is what the counterfactual walk anchors on.
                rs.getObject("reference_bar_date", java.time.LocalDate.class),
                rs.getBigDecimal("reference_atr"));
    }

    private String writeJson(List<String> v) {
        try { return mapper.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-signal killCriteria", e); }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }

    private String writeThesis(JsonNode node) {
        if (node == null) return null;                       // SQL NULL, never {} husk
        try { return mapper.writeValueAsString(node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize executor-signal thesis", e); }
    }

    private JsonNode readThesis(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); }
        catch (Exception e) { log.error("Failed to deserialize executor-signal thesis: {}", json, e); return null; }
    }
}
