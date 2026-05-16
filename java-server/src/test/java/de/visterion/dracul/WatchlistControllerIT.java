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

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistControllerIT {

    @LocalServerPort
    int port;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.create("http://localhost:" + port);
    }

    @Test
    void watchlistReturns8ItemsWithCorrectAlertCounts() {
        ResponseEntity<JsonNode> response = rest.get()
                .uri("/api/watchlist")
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(8);

        var avgo = StreamSupport.stream(response.getBody().spliterator(), false)
                .filter(item -> "AVGO".equals(item.get("ticker").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(avgo.get("alerts")).hasSize(3);

        var meli = StreamSupport.stream(response.getBody().spliterator(), false)
                .filter(item -> "MELI".equals(item.get("ticker").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(meli.get("alerts")).isEmpty();
    }

    @Test
    void watchlistAvgoHasVerdictId() {
        ResponseEntity<JsonNode> response = rest.get()
                .uri("/api/watchlist")
                .retrieve()
                .toEntity(JsonNode.class);

        var avgo = StreamSupport.stream(response.getBody().spliterator(), false)
                .filter(item -> "AVGO".equals(item.get("ticker").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(avgo.get("verdictId").asText())
                .isEqualTo("b0000000-0000-0000-0000-000000000001");
    }
}
