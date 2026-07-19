package de.visterion.dracul.research;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link ResearchMemoryLink} rows — the prey-to-memory-cell links Task 9 writes and
 * Task 10 scans to resolve realized outcomes back to the originating HiveMem thesis cell.
 */
@Repository
public class ResearchMemoryLinkRepository {

    private final JdbcClient jdbc;

    public ResearchMemoryLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new link. Violates {@code unique(kind, refId)} on collision — the caller
     *  is expected to handle/propagate that as a data-integrity error. */
    public long insert(String kind, String refId, String symbol, String cellId) {
        return jdbc.sql("""
                INSERT INTO research_memory_link (kind, ref_id, symbol, cell_id)
                VALUES (:kind, :refId, :symbol, :cellId)
                RETURNING id
                """)
                .param("kind", kind)
                .param("refId", refId)
                .param("symbol", symbol)
                .param("cellId", cellId)
                .query(Long.class)
                .single();
    }

    /** Links not yet resolved to an outcome, oldest first — the basis for Task 10's outcome scan. */
    public List<ResearchMemoryLink> findUnwrittenPreyLinks(int limit) {
        return jdbc.sql("""
                SELECT * FROM research_memory_link
                WHERE kind = 'prey' AND outcome_written = false
                ORDER BY created_at ASC LIMIT :lim
                """)
                .param("lim", limit)
                .query(this::mapRow)
                .list();
    }

    /** Flips the outcome-written flag. Idempotent — a second call on an already-written row
     *  is a no-op. */
    public void markOutcomeWritten(long id) {
        jdbc.sql("UPDATE research_memory_link SET outcome_written = true WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** Terminal scan-bound exclusion (never-traded, too old) — removes the link from the
     *  unwritten scan the same way {@link #markOutcomeWritten} does, since there is no
     *  separate "excluded" state in the schema: both mean "stop scanning this row". */
    public void markExcluded(long id) {
        jdbc.sql("UPDATE research_memory_link SET outcome_written = true WHERE id = :id")
                .param("id", id)
                .update();
    }

    public Optional<ResearchMemoryLink> findByPreyRefId(String preyId) {
        return jdbc.sql("SELECT * FROM research_memory_link WHERE kind = 'prey' AND ref_id = :refId")
                .param("refId", preyId)
                .query(this::mapRow)
                .optional();
    }

    private ResearchMemoryLink mapRow(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        return new ResearchMemoryLink(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("ref_id"),
                rs.getString("symbol"),
                rs.getString("cell_id"),
                createdAt == null ? null : createdAt.toInstant(),
                rs.getBoolean("outcome_written"));
    }
}
