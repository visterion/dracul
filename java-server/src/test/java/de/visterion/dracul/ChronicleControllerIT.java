package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ChronicleControllerIT {

    @LocalServerPort
    int port;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.create("http://localhost:" + port);
    }

    @Test
    void chronicleReturns200WithExpectedCounts() {
        ResponseEntity<JsonNode> response = rest.get()
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
}
