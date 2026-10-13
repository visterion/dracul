package de.visterion.dracul.benchmark;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ScreenCandidateRepositoryIT {

    @Autowired ScreenCandidateRepository repo;
    @Autowired JdbcClient jdbc;

    private static String uniqueAgent() {
        return "strigoi-syn" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void secondInsertOfTheSameAgentUtcDayAndSymbolIsADuplicate() {
        String agent = uniqueAgent();
        ScreenItem item = new ScreenItem("SYNA", new BigDecimal("1.5"), null);

        assertThat(repo.insert(agent, Instant.parse("2026-07-14T08:00:00Z"), item, "BACKFILL")).isTrue();
        assertThat(repo.insert(agent, Instant.parse("2026-07-14T21:00:00Z"), item, "LIVE")).isFalse();
        assertThat(repo.insert(agent, Instant.parse("2026-07-15T08:00:00Z"), item, "LIVE")).isTrue();

        Map<String, Object> row = jdbc.sql("""
                SELECT as_of_date, source, score, extra FROM screen_candidate
                 WHERE agent = :agent ORDER BY as_of_date LIMIT 1""")
                .param("agent", agent).query().singleRow();
        assertThat(row.get("as_of_date").toString()).isEqualTo("2026-07-14");
        assertThat(row.get("source")).isEqualTo("BACKFILL");
        assertThat((BigDecimal) row.get("score")).isEqualByComparingTo("1.5");
        assertThat(row.get("extra")).isNull();
    }

    @Test
    void extraIsStoredAsJsonb() {
        String agent = uniqueAgent();
        ObjectNode extra = JsonNodeFactory.instance.objectNode().put("offerPrice", new BigDecimal("52.00"));
        repo.insert(agent, Instant.parse("2026-07-14T08:00:00Z"), new ScreenItem("SYNA", null, extra), "LIVE");

        String offer = jdbc.sql("SELECT extra->>'offerPrice' FROM screen_candidate WHERE agent = :agent")
                .param("agent", agent).query(String.class).single();
        assertThat(new BigDecimal(offer)).isEqualByComparingTo("52.00");
    }

    @Test
    void coverageStartIsTheFirstAsOfDatePerAgent() {
        String agent = uniqueAgent();
        repo.insert(agent, Instant.parse("2026-07-15T08:00:00Z"), new ScreenItem("SYNB", null, null), "LIVE");
        repo.insert(agent, Instant.parse("2026-07-14T08:00:00Z"), new ScreenItem("SYNA", null, null), "BACKFILL");

        assertThat(repo.coverageStart()).containsEntry(agent, LocalDate.of(2026, 7, 14));
    }
}
