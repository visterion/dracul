package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
    void watchlistReturns8ItemsWithCorrectAlertCounts() {
        var response = rest.get()
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
        var response = rest.get()
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
