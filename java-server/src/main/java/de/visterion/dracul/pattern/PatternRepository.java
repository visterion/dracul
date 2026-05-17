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
