package de.visterion.dracul.benchmark;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** {@code screen_candidate} (V55). One row per (agent, UTC day, symbol). */
@Repository
public class ScreenCandidateRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ScreenCandidateRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return true when stored, false when (agent, as_of_date, symbol) already existed. */
    public boolean insert(String agent, Instant fetchedAt, ScreenItem item, String source) {
        int n = jdbc.sql("""
                INSERT INTO screen_candidate (id, agent, fetched_at, as_of_date, symbol, score, extra, source)
                VALUES (:id, :agent, :fetchedAt, :asOfDate, :symbol, :score, CAST(:extra AS jsonb), :source)
                ON CONFLICT (agent, as_of_date, symbol) DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("agent", agent)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("asOfDate", fetchedAt.atZone(ZoneOffset.UTC).toLocalDate())
                .param("symbol", item.symbol())
                .param("score", item.score())
                .param("extra", item.extra() == null ? null : mapper.writeValueAsString(item.extra()))
                .param("source", source)
                .update();
        return n == 1;
    }

    /** First captured day per agent — the UI's "Daten seit" (spec §2.4: computed, not hard-coded). */
    public Map<String, LocalDate> coverageStart() {
        Map<String, LocalDate> out = new TreeMap<>();
        jdbc.sql("SELECT agent, min(as_of_date) AS first_day FROM screen_candidate GROUP BY agent")
                .query((rs, i) -> Map.entry(rs.getString("agent"), rs.getObject("first_day", LocalDate.class)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
