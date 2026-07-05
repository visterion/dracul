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
class VerdictNotesControllerIT {

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
    void postCreatesNoteAndGetReturnsItDesc() {
        String id = anyVerdictId();

        rest.post().uri("/api/verdict/" + id + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", "first thought"))
                .retrieve().toBodilessEntity();
        rest.post().uri("/api/verdict/" + id + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", "second thought"))
                .retrieve().toBodilessEntity();

        JsonNode resp = rest.get().uri("/api/verdict/" + id + "/notes")
                .retrieve().body(JsonNode.class);
        assertThat(resp.get("notes")).hasSize(2);
        assertThat(resp.get("notes").get(0).get("body").asText()).isEqualTo("second thought");
        assertThat(resp.get("notes").get(1).get("body").asText()).isEqualTo("first thought");
    }

    @Test
    void postReturns400OnEmptyBody() {
        String id = anyVerdictId();
        assertThatThrownBy(() -> rest.post().uri("/api/verdict/" + id + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", "  "))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void postReturns404ForUnknownVerdict() {
        assertThatThrownBy(() -> rest.post()
                .uri("/api/verdict/00000000-0000-0000-0000-000000000000/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", "x"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
