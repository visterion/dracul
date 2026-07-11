package de.visterion.dracul.pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PatternRepository {

    private final JdbcClient jdbc;

    public PatternRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Pattern> findAllByUser(String userId) {
        return jdbc.sql("""
                SELECT id, applies_to_strigoi, statement, status, evidence_count,
                       proposed_at, supported_count, avg_uplift_percent, name
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
                       proposed_at, supported_count, avg_uplift_percent, name
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
                       proposed_at, supported_count, avg_uplift_percent, name
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
                rs.getString("name")
        );
    }
}
