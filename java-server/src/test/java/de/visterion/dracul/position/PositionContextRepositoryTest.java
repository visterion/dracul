package de.visterion.dracul.position;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PositionContextRepositoryTest {

    @Autowired PositionContextRepository repo;
    @Autowired JdbcClient jdbc;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM position_context").update();
    }

    @Test
    void upsertOnOpenTwiceIsIdempotentWhileOpen() {
        var kill = JSON.createObjectNode().put("rule", "close below 50dma");
        var thesis = JSON.createObjectNode().put("summary", "spin-off dividend recap");

        String first = repo.upsertOnOpen("depot-1", "ACME", "verdict-1", kill,
                "3-6mo", thesis, new BigDecimal("10.00"), "strigoi-spin");
        String second = repo.upsertOnOpen("depot-1", "ACME", "verdict-1", kill,
                "3-6mo", thesis, new BigDecimal("10.00"), "strigoi-spin");

        assertThat(second).isEqualTo(first);

        Integer count = jdbc.sql("SELECT count(*) FROM position_context WHERE connection = :c AND lower(symbol) = :s")
                .param("c", "depot-1").param("s", "acme")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void findOpenBySymbolAndFindAllOpenReturnTheOpenRow() {
        String id = repo.upsertOnOpen("depot-1", "GLOB", "verdict-2", null,
                "12mo", null, new BigDecimal("5.00"), "strigoi-spin");

        PositionContextRow found = repo.findOpenBySymbol("depot-1", "GLOB").orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.connection()).isEqualTo("depot-1");
        assertThat(found.symbol()).isEqualTo("GLOB");
        assertThat(found.verdictId()).isEqualTo("verdict-2");
        assertThat(found.closedAt()).isNull();

        List<PositionContextRow> allOpen = repo.findAllOpen("depot-1");
        assertThat(allOpen).extracting(PositionContextRow::id).contains(id);
    }

    @Test
    void markClosedThenUpsertOnOpenCreatesNewOpenRow() {
        String firstId = repo.upsertOnOpen("depot-1", "REOP", "verdict-3", null,
                "3mo", null, new BigDecimal("1.00"), "strigoi-spin");

        repo.markClosed(firstId);
        assertThat(repo.findOpenBySymbol("depot-1", "REOP")).isEmpty();

        String secondId = repo.upsertOnOpen("depot-1", "REOP", "verdict-4", null,
                "3mo", null, new BigDecimal("2.00"), "strigoi-spin");

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(repo.findOpenBySymbol("depot-1", "REOP").orElseThrow().id()).isEqualTo(secondId);

        Integer count = jdbc.sql("SELECT count(*) FROM position_context WHERE connection = :c AND lower(symbol) = :s")
                .param("c", "depot-1").param("s", "reop")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void updateStopsPersistsBothValues() {
        String id = repo.upsertOnOpen("depot-1", "STOP", "verdict-5", null,
                "6mo", null, new BigDecimal("10.00"), "strigoi-spin");

        repo.updateStops(id, new BigDecimal("9.50"), new BigDecimal("11.25"));

        PositionContextRow row = repo.findOpenBySymbol("depot-1", "STOP").orElseThrow();
        assertThat(row.initialStop()).isEqualByComparingTo("9.50");
        assertThat(row.activeStop()).isEqualByComparingTo("11.25");
    }

    @Test
    void updateContextIfNull_healsShadowRowThenNoOps() {
        // reconciler-style "none" shadow row: all research fields null
        repo.upsertOnOpen("depot-1", "HELE", null, null, null, null, null, "none");

        JsonNode thesis = JSON.readTree("{\"summary\":\"beat\"}");
        JsonNode kill = JSON.readTree("[\"drift reverses\"]");
        repo.updateContextIfNull("depot-1", "HELE", thesis, kill, "1M", new BigDecimal("178.19"));

        var row = repo.findOpenBySymbol("depot-1", "HELE").orElseThrow();
        assertThat(row.thesisSnapshot().get("summary").asString()).isEqualTo("beat");
        assertThat(row.horizon()).isEqualTo("1M");
        assertThat(row.initialStop()).isEqualByComparingTo("178.19");

        // COALESCE never clobbers: a second call with different values changes nothing
        repo.updateContextIfNull("depot-1", "HELE",
                JSON.readTree("{\"summary\":\"other\"}"), null, "6M", new BigDecimal("1"));
        assertThat(repo.findOpenBySymbol("depot-1", "HELE").orElseThrow()
                .thesisSnapshot().get("summary").asString()).isEqualTo("beat");
    }

    @Test
    void updateContextIfNull_caseInsensitiveSymbol() {
        repo.upsertOnOpen("depot-1", "HELE", null, null, null, null, null, "none");
        repo.updateContextIfNull("depot-1", "hele", JSON.readTree("{\"summary\":\"x\"}"), null, null, null);
        assertThat(repo.findOpenBySymbol("depot-1", "HELE").orElseThrow().thesisSnapshot()).isNotNull();
    }

    @Test
    void updateVerdictLink_linksAndHealsOnlyUnlinkedOpenRow() {
        repo.upsertOnOpen("depot-1", "HELE", null, null, null, null, null, "none");
        repo.updateVerdictLink("depot-1", "HELE", "v1",
                JSON.readTree("{\"summary\":\"v\"}"), JSON.readTree("[\"k\"]"), "1M");
        var row = repo.findOpenBySymbol("depot-1", "HELE").orElseThrow();
        assertThat(row.verdictId()).isEqualTo("v1");
        assertThat(row.horizon()).isEqualTo("1M");

        // already linked → never overwritten
        repo.updateVerdictLink("depot-1", "HELE", "v2", null, null, null);
        assertThat(repo.findOpenBySymbol("depot-1", "HELE").orElseThrow().verdictId()).isEqualTo("v1");
    }
}
