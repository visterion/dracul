package de.visterion.dracul.notes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class VerdictNotesRepository {

    private final JdbcClient jdbc;
    public VerdictNotesRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean verdictExists(String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        return jdbc.sql("SELECT 1 FROM verdicts WHERE id = :id")
                .param("id", uuid)
                .query(Integer.class)
                .optional().isPresent();
    }

    public VerdictNote insert(String verdictId, String body) {
        UUID id = UUID.randomUUID();
        UUID vid = UUID.fromString(verdictId);
        int rows = jdbc.sql("""
                INSERT INTO verdict_notes (id, verdict_id, body, created_at, user_id)
                VALUES (:id, :vid, :body, now(), 'default')
                """)
                .param("id", id).param("vid", vid).param("body", body)
                .update();
        if (rows != 1) throw new IllegalStateException("insert failed for verdict " + verdictId);
        String createdAt = jdbc.sql("SELECT created_at::text FROM verdict_notes WHERE id = :id")
                .param("id", id).query(String.class).single();
        return new VerdictNote(id.toString(), verdictId, body, createdAt);
    }

    public List<VerdictNote> findByVerdictId(String verdictId) {
        UUID vid;
        try { vid = UUID.fromString(verdictId); }
        catch (IllegalArgumentException e) { return List.of(); }
        return jdbc.sql("""
                SELECT id, verdict_id, body, created_at::text AS created_at
                FROM verdict_notes
                WHERE verdict_id = :vid
                ORDER BY created_at DESC
                """)
                .param("vid", vid)
                .query((rs, rowNum) -> new VerdictNote(
                        rs.getString("id"),
                        rs.getString("verdict_id"),
                        rs.getString("body"),
                        rs.getString("created_at")))
                .list();
    }
}
