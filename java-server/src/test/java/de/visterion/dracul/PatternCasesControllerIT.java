package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternCasesControllerIT {

    static final String PENDING_ID_2 = "c0000000-0000-0000-0000-000000000002";
    static final String UNKNOWN_ID   = "c0000000-0000-0000-0000-0000000000ff";

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
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
    }

    @Test
    void returns200WithCasesForPendingPattern() {
        var response = rest.get()
                .uri("/api/patterns/" + PENDING_ID_2 + "/cases")
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(28);

        var first = body.get(0);
        assertThat(first.path("symbol").asText()).isNotBlank();
        assertThat(first.path("companyName").asText()).isNotBlank();
        assertThat(first.path("anomalyType").asText()).isNotBlank();
        assertThat(first.path("occurredAt").asText()).isNotBlank();
        assertThat(first.has("supported")).isTrue();
    }

    @Test
    void returns404ForUnknownPattern() {
        var response = rest.get()
                .uri("/api/patterns/" + UNKNOWN_ID + "/cases")
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {})
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
