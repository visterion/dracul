package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
