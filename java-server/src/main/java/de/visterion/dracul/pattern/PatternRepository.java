package de.visterion.dracul.pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PatternRepository {

    private final JdbcClient jdbc;

    public PatternRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Pattern> findAllByUser(String userId) {
        return jdbc.sql("""
                SELECT id, applies_to_strigoi, statement, status, evidence_count,
                       proposed_at, supported_count, avg_uplift_percent, name, gate::text AS gate
                FROM patterns
                WHERE user_id = :userId
                ORDER BY proposed_at DESC
                """)
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    public List<Pattern> findPendingByUser(String userId) {
        return jdbc.sql("""
                SELECT id, applies_to_strigoi, statement, status, evidence_count,
                       proposed_at, supported_count, avg_uplift_percent, name, gate::text AS gate
                FROM patterns
                WHERE user_id = :userId AND status = 'PENDING'
                ORDER BY proposed_at DESC
                """)
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    public java.util.Optional<Pattern> findById(String id, String userId) {
        return jdbc.sql("""
                SELECT id, applies_to_strigoi, statement, status, evidence_count,
                       proposed_at, supported_count, avg_uplift_percent, name, gate::text AS gate
                FROM patterns
                WHERE id = :id::uuid AND user_id = :userId
                """)
                .param("id", id)
                .param("userId", userId)
                .query(this::mapRow)
                .optional();
    }

    public List<PatternCase> findCases(String patternId, String userId) {
        return jdbc.sql("""
                SELECT e.symbol, e.company_name, e.anomaly_type, e.occurred_at,
                       e.supported, e.return_percent, e.note
                FROM pattern_evidence e
                JOIN patterns p ON p.id = e.pattern_id
                WHERE e.pattern_id = :patternId::uuid AND p.user_id = :userId
                ORDER BY e.occurred_at DESC
                """)
                .param("patternId", patternId)
                .param("userId", userId)
                .query(this::mapCaseRow)
                .list();
    }

    /** Accepted-pattern statements relevant to a hunter's fetch response: ACTIVE
     *  patterns scoped to this strigoi plus ACTIVE patterns scoped to 'all'.
     *  Used to feed the learning loop back into hunter tool-fetch output
     *  ({@code active_patterns}) — see HuntController#handleFetch. */
    public List<String> findAcceptedByStrigoi(String strigoi) {
        return jdbc.sql("""
                SELECT statement FROM patterns
                WHERE status = 'ACTIVE' AND (applies_to_strigoi = :strigoi OR applies_to_strigoi = 'all')
                ORDER BY proposed_at DESC
                """)
                .param("strigoi", strigoi)
                .query(String.class)
                .list();
    }

    /** Every ACTIVE pattern statement regardless of scope — used by Voievod, which
     *  reviews consensus clusters spanning multiple hunters and so benefits from the
     *  full accepted-lesson set rather than a single strigoi's slice. */
    public List<String> findAllAccepted() {
        return jdbc.sql("""
                SELECT statement FROM patterns
                WHERE status = 'ACTIVE'
                ORDER BY proposed_at DESC
                """)
                .query(String.class)
                .list();
    }

    public void updateStatus(String id, String userId, String status) {
        jdbc.sql("UPDATE patterns SET status = :status WHERE id = :id::uuid AND user_id = :userId")
                .param("status", status)
                .param("id", id)
                .param("userId", userId)
                .update();
    }

    public void setName(String id, String userId, String name) {
        jdbc.sql("UPDATE patterns SET name = :name WHERE id = :id::uuid AND user_id = :userId")
                .param("name", name)
                .param("id", id)
                .param("userId", userId)
                .update();
    }

    /** True when a PENDING pattern with an identical statement already exists for the
     *  user — used by the voievod-outcome completion handler to dedupe proposals. */
    public boolean existsPendingStatement(String userId, String statement) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM patterns
                WHERE user_id = :userId AND status = 'PENDING' AND statement = :statement
                """)
                .param("userId", userId)
                .param("statement", statement)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** Inserts a new PENDING pattern proposal from the voievod-outcome agent. */
    public void insertProposal(String userId, String appliesToStrigoi, String statement,
                                int evidenceCount) {
        jdbc.sql("""
                INSERT INTO patterns (id, applies_to_strigoi, statement, status,
                                      evidence_count, proposed_at, user_id)
                VALUES (gen_random_uuid(), :strigoi, :statement, 'PENDING',
                        :evidence, now(), :userId)
                """)
                .param("strigoi", appliesToStrigoi)
                .param("statement", statement)
                .param("evidence", evidenceCount)
                .param("userId", userId)
                .update();
    }

    /** Overload of {@link #insertProposal(String, String, String, int)} carrying the
     *  LLM-proposed (already validated) gate JSON. The 4-arg variant stays untouched so
     *  gate-less proposals keep today's exact call path. */
    public void insertProposal(String userId, String appliesToStrigoi, String statement,
                                int evidenceCount, String gateJson) {
        jdbc.sql("""
                INSERT INTO patterns (id, applies_to_strigoi, statement, status,
                                      evidence_count, proposed_at, user_id, gate)
                VALUES (gen_random_uuid(), :strigoi, :statement, 'PENDING',
                        :evidence, now(), :userId, CAST(:gate AS jsonb))
                """)
                .param("strigoi", appliesToStrigoi)
                .param("statement", statement)
                .param("evidence", evidenceCount)
                .param("userId", userId)
                .param("gate", gateJson)
                .update();
    }

    /** Enforced gates for the veto path (spec T3.3 D4): ACTIVE + gate, single-tenant
     *  scoped so a future second user's gates can never veto the default book. Order is
     *  the deterministic first-match evaluation order. */
    public List<EnforcedGate> findEnforced() {
        return jdbc.sql("""
                SELECT id, name, gate::text AS gate FROM patterns
                WHERE user_id = 'default' AND status = 'ACTIVE' AND gate IS NOT NULL
                ORDER BY proposed_at DESC, id
                """)
                .query((rs, n) -> new EnforcedGate(rs.getString("id"), rs.getString("name"),
                        rs.getString("gate")))
                .list();
    }

    /** Scorer input (spec T3.3 D5): every PENDING or ACTIVE pattern with a gate. Scoring
     *  PENDING gates is deliberate — the operator gets match evidence before approving. */
    public List<Pattern> findGatedForScoring() {
        return jdbc.sql("""
                SELECT id, applies_to_strigoi, statement, status, evidence_count,
                       proposed_at, supported_count, avg_uplift_percent, name, gate::text AS gate
                FROM patterns
                WHERE user_id = 'default' AND status IN ('PENDING', 'ACTIVE')
                  AND gate IS NOT NULL
                ORDER BY proposed_at DESC, id
                """)
                .query(this::mapRow)
                .list();
    }

    /** Idempotent auto-evidence insert (spec T3.3 D5). {@code occurredAt} is an ISO
     *  timestamp string cast server-side; ON CONFLICT DO NOTHING rides the partial
     *  unique index (pattern_id, outcome_ref). Returns true when a row was inserted. */
    public boolean insertAutoEvidence(String patternId, String symbol, String companyName,
            String anomalyType, String occurredAt, boolean supported, BigDecimal returnPercent,
            String outcomeRef) {
        int rows = jdbc.sql("""
                INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type,
                                              occurred_at, supported, return_percent, note,
                                              outcome_ref)
                VALUES (:patternId::uuid, :symbol, :companyName, :anomalyType,
                        :occurredAt::timestamptz, :supported, :returnPercent, 'scored:auto',
                        :outcomeRef)
                ON CONFLICT DO NOTHING
                """)
                .param("patternId", patternId)
                .param("symbol", symbol)
                .param("companyName", companyName)
                .param("anomalyType", anomalyType)
                .param("occurredAt", occurredAt)
                .param("supported", supported)
                .param("returnPercent", returnPercent)
                .param("outcomeRef", outcomeRef)
                .update();
        return rows > 0;
    }

    /** Aggregate recompute over runtime evidence rows only (outcome_ref IS NOT NULL), with
     *  the spec's GREATEST rule for evidence_count and the only-if-runtime-rows-exist guard
     *  (agg.cnt > 0) so seed-/proposal-only patterns are never wiped to 0 (spec T3.3 D5). */
    public void recomputeAggregates(String patternId) {
        jdbc.sql("""
                UPDATE patterns p
                SET supported_count = agg.sup,
                    avg_uplift_percent = agg.avg_up,
                    evidence_count = GREATEST(p.evidence_count, agg.cnt)
                FROM (SELECT COUNT(*) FILTER (WHERE supported) AS sup,
                             AVG(return_percent) AS avg_up,
                             COUNT(*) AS cnt
                      FROM pattern_evidence
                      WHERE pattern_id = :id::uuid AND outcome_ref IS NOT NULL) agg
                WHERE p.id = :id::uuid AND agg.cnt > 0
                """)
                .param("id", patternId)
                .update();
    }

    /** Gate replace/clear with in-transaction auto-evidence invalidation (spec T3.3 D5/D6):
     *  the old predicate's runtime evidence rows are deleted; when any were deleted the
     *  runtime-derived aggregates are cleared to NULL ("no evidence yet" — the next scorer
     *  full rescan rebuilds them under the new predicate) while evidence_count keeps the
     *  GREATEST-protected LLM count. Seed/manual rows (outcome_ref IS NULL) are untouched,
     *  and a pattern that never had runtime evidence keeps all its numbers. Returns the
     *  number of updated pattern rows (0 = not found for user). */
    @Transactional
    public int replaceGate(String id, String userId, String gateJson) {
        int updated = jdbc.sql("""
                UPDATE patterns SET gate = CAST(:gate AS jsonb)
                WHERE id = :id::uuid AND user_id = :userId
                """)
                .param("gate", gateJson)
                .param("id", id)
                .param("userId", userId)
                .update();
        if (updated == 0) return 0;
        int deleted = jdbc.sql("""
                DELETE FROM pattern_evidence
                WHERE pattern_id = :id::uuid AND outcome_ref IS NOT NULL
                """)
                .param("id", id)
                .update();
        if (deleted > 0) {
            jdbc.sql("""
                    UPDATE patterns SET supported_count = NULL, avg_uplift_percent = NULL
                    WHERE id = :id::uuid AND user_id = :userId
                    """)
                    .param("id", id)
                    .param("userId", userId)
                    .update();
        }
        return updated;
    }

    /** blocked_count per pattern id (spec T3.3 D6): COUNT(DISTINCT signal_id) over
     *  decision_log rows whose reason_code IS 'PATTERN_GATE' (load-bearing — the trace
     *  contains a PATTERN_GATE entry for every evaluated signal), id parsed from the gate
     *  check's measured field ("pattern_gate:<id> (<name>)"). Computed at read time,
     *  never persisted. */
    public Map<String, Long> blockedCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT substring(e.elem->>'measured' FROM '^pattern_gate:([0-9a-f\\-]+)')
                           AS pattern_id,
                       COUNT(DISTINCT d.signal_id) AS blocked
                FROM decision_log d
                CROSS JOIN LATERAL jsonb_array_elements(d.veto_results) AS e(elem)
                WHERE d.reason_code = 'PATTERN_GATE'
                  AND e.elem->>'check' = 'PATTERN_GATE'
                  AND (e.elem->>'passed')::boolean = false
                GROUP BY 1
                """)
                .query((rs, n) -> {
                    counts.put(rs.getString("pattern_id"), rs.getLong("blocked"));
                    return null;
                })
                .list();
        return counts;
    }

    private PatternCase mapCaseRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var returnPercent = rs.getObject("return_percent");
        return new PatternCase(
                rs.getString("symbol"),
                rs.getString("company_name"),
                rs.getString("anomaly_type"),
                rs.getString("occurred_at"),
                rs.getBoolean("supported"),
                returnPercent == null ? null : ((Number) returnPercent).doubleValue(),
                rs.getString("note")
        );
    }

    private Pattern mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var uplift = rs.getObject("avg_uplift_percent");
        var supported = rs.getObject("supported_count");
        return new Pattern(
                rs.getString("id"),
                rs.getString("applies_to_strigoi"),
                rs.getString("statement"),
                rs.getString("status"),
                rs.getInt("evidence_count"),
                rs.getString("proposed_at"),
                supported == null ? null : ((Number) supported).intValue(),
                uplift == null ? null : ((Number) uplift).doubleValue(),
                rs.getString("name"),
                rs.getString("gate")
        );
    }
}
