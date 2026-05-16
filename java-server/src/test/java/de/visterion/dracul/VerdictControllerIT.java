package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class VerdictControllerIT {

    @LocalServerPort
    int port;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.create("http://localhost:" + port);
    }

    @Test
    void verdictDetailReturnsAvgoByUuid() {
        ResponseEntity<JsonNode> response = rest.get()
                .uri("/api/verdict/b0000000-0000-0000-0000-000000000001")
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("symbol").asText()).isEqualTo("AVGO");
        assertThat(response.getBody().get("contributingDetails")).hasSize(3);
    }

    @Test
    void verdictDetailReturns404ForUnknownId() {
        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> rest.get()
                        .uri("/api/verdict/00000000-0000-0000-0000-000000000000")
                        .retrieve()
                        .toEntity(String.class)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
