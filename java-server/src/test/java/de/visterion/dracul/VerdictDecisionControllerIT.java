package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class VerdictDecisionControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper om;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(om)); })
                .build();
    }

    private String anyVerdictId() {
        JsonNode arr = rest.get().uri("/api/chronicle").retrieve().body(JsonNode.class);
        return arr.get("verdicts").get(0).get("id").asText();
    }

    @Test
    void putDecisionSetsValueAndDecidedAt() {
        String id = anyVerdictId();

        JsonNode resp = rest.put().uri("/api/verdict/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "TRACK"))
                .retrieve().body(JsonNode.class);

        assertThat(resp.get("decision").asText()).isEqualTo("TRACK");
        assertThat(resp.get("decidedAt").asText()).isNotBlank();

        JsonNode detail = rest.get().uri("/api/verdict/" + id)
                .retrieve().body(JsonNode.class);
        assertThat(detail.get("decision").asText()).isEqualTo("TRACK");
    }

    @Test
    void putNullClearsDecision() {
        String id = anyVerdictId();
        rest.put().uri("/api/verdict/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "INTERESTING"))
                .retrieve().toBodilessEntity();

        JsonNode body = om.createObjectNode().putNull("decision");
        rest.put().uri("/api/verdict/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();

        JsonNode detail = rest.get().uri("/api/verdict/" + id)
                .retrieve().body(JsonNode.class);
        assertThat(detail.get("decision").isNull()).isTrue();
    }

    @Test
    void putReturns404ForUnknownId() {
        assertThatThrownBy(() -> rest.put()
                .uri("/api/verdict/00000000-0000-0000-0000-000000000000/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "TRACK"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void putReturns400ForInvalidEnum() {
        String id = anyVerdictId();
        assertThatThrownBy(() -> rest.put()
                .uri("/api/verdict/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "BOGUS"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
