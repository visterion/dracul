package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ChronicleControllerIT {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
        resetSeedBaseline();
    }

    /**
     * The chronicle endpoint aggregates absolute counts of the {@code default}-user seed
     * rows (prey/verdicts/patterns). The Postgres testcontainer is shared and reused across
     * every IT, and several of them mutate those seed rows without restoring them — most
     * notably {@code PatternActionControllerIT}, which approves/rejects the seed PENDING
     * patterns (01 -> ACTIVE, 02 -> REJECTED) and never puts them back, dropping the pending
     * count from 3 to 1. This restores the canonical seed baseline before each test so the
     * count assertions are deterministic regardless of execution order. It only touches
     * {@code default}-user rows and only removes non-seed pollution (seed ids carry the fixed
     * a/b/c prefixes from V2__seed.sql), so it never weakens what the assertions verify.
     */
    private void resetSeedBaseline() {
        // Restore the three seed patterns that other ITs flip away from PENDING.
        jdbc.sql("""
                UPDATE patterns SET status = 'PENDING'
                WHERE id IN ('c0000000-0000-0000-0000-000000000001',
                             'c0000000-0000-0000-0000-000000000002',
                             'c0000000-0000-0000-0000-000000000003')
                """).update();
        // Drop any non-seed default-user rows left behind by other ITs (children cascade).
        jdbc.sql("DELETE FROM patterns WHERE user_id = 'default' AND id::text NOT LIKE 'c0000000-%'").update();
        jdbc.sql("DELETE FROM prey     WHERE user_id = 'default' AND id::text NOT LIKE 'a0000000-%'").update();
        jdbc.sql("DELETE FROM verdicts WHERE user_id = 'default' AND id::text NOT LIKE 'b0000000-%'").update();
        // Clear any decision left on the seed verdict (e.g. by a verdict-decision IT).
        jdbc.sql("UPDATE verdicts SET decision = NULL WHERE id = 'b0000000-0000-0000-0000-000000000001'").update();
    }

    @Test
    void chronicleReturns200WithExpectedCounts() {
        var response = rest.get()
                .uri("/api/chronicle?includeDismissed=true")
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("prey")).hasSize(5);
        assertThat(body.get("verdicts")).hasSize(1);
        assertThat(body.get("alerts")).isEmpty();
        assertThat(body.get("pendingPatterns")).hasSize(3);
    }

    @Test
    void chronicleFiltersDismissedVerdictsByDefault() {
        JsonNode all = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        int total = all.get("verdicts").size();
        String verdictId = all.get("verdicts").get(0).get("id").asText();

        try {
            rest.put().uri("/api/verdict/" + verdictId + "/decision")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("decision", "DISMISS"))
                    .retrieve().toBodilessEntity();

            JsonNode filtered = rest.get().uri("/api/chronicle").retrieve().body(JsonNode.class);
            assertThat(filtered.get("verdicts").size()).isEqualTo(total - 1);

            JsonNode included = rest.get().uri("/api/chronicle?includeDismissed=true")
                    .retrieve().body(JsonNode.class);
            assertThat(included.get("verdicts").size()).isEqualTo(total);
        } finally {
            ObjectNode body = objectMapper.createObjectNode();
            body.putNull("decision");
            rest.put().uri("/api/verdict/" + verdictId + "/decision")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toBodilessEntity();
        }
    }
}
