package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChronicleControllerIT {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new MappingJackson2HttpMessageConverter(objectMapper));
                })
                .build();
    }

    @Test
    void aChronicleReturns200WithExpectedCounts() {
        var response = rest.get()
                .uri("/api/chronicle")
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
    void bChronicleFiltersDismissedVerdictsByDefault() {
        // Get initial verdict count - should have exactly 1 non-dismissed verdict
        JsonNode initial = rest.get().uri("/api/chronicle").retrieve().body(JsonNode.class);
        assertThat(initial.get("verdicts")).isNotEmpty();
        String verdictId = initial.get("verdicts").get(0).get("id").asText();

        // Mark the first verdict as DISMISS
        rest.put().uri("/api/verdict/" + verdictId + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "DISMISS"))
                .retrieve().toBodilessEntity();

        // Now the filtered endpoint (default behavior) should exclude it
        JsonNode filtered = rest.get().uri("/api/chronicle").retrieve().body(JsonNode.class);
        assertThat(filtered.get("verdicts")).hasSize(0);

        // But the includeDismissed=true query should still show it
        JsonNode allVerdicts = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        assertThat(allVerdicts.get("verdicts")).hasSize(1);
        assertThat(allVerdicts.get("verdicts").get(0).get("id").asText()).isEqualTo(verdictId);
    }
}
