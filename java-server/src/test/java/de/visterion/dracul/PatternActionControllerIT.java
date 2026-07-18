package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternActionControllerIT {

    static final String PENDING_ID_1 = "c0000000-0000-0000-0000-000000000001";
    static final String PENDING_ID_2 = "c0000000-0000-0000-0000-000000000002";
    static final String PENDING_ID_3 = "c0000000-0000-0000-0000-000000000003";
    static final String ACTIVE_ID    = "c0000000-0000-0000-0000-000000000004";

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired JdbcClient jdbc;
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

        // Container reuse (ContainerConfig withReuse=true) leaves seed rows in whatever
        // state the previous run put them; approve is now PENDING-only, so pin the fixture.
        jdbc.sql("UPDATE patterns SET status = 'PENDING' WHERE id IN (:a::uuid, :b::uuid, :c::uuid)")
                .param("a", PENDING_ID_1).param("b", PENDING_ID_2).param("c", PENDING_ID_3)
                .update();
        jdbc.sql("UPDATE patterns SET status = 'ACTIVE' WHERE id = :id::uuid")
                .param("id", ACTIVE_ID).update();
    }

    @Test
    void approveSetStatusToActiveAndGeneratesSlug() {
        var response = rest.patch()
                .uri("/api/patterns/" + PENDING_ID_1)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("action", "approve"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);

        var patterns = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        var found = StreamSupport.stream(patterns.getBody().spliterator(), false)
                .filter(p -> PENDING_ID_1.equals(p.path("id").asText()))
                .findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(found.get().path("name").asText()).isNotBlank();
        assertThat(found.get().path("name").asText()).contains("-");
    }

    @Test
    void rejectSetStatusToRejected() {
        var response = rest.patch()
                .uri("/api/patterns/" + PENDING_ID_2)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("action", "reject"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);

        var patterns = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        var found = StreamSupport.stream(patterns.getBody().spliterator(), false)
                .filter(p -> PENDING_ID_2.equals(p.path("id").asText()))
                .findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().path("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void deferReturns204WithNoStatusChange() {
        var before = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        var statusBefore = StreamSupport.stream(before.getBody().spliterator(), false)
                .filter(p -> PENDING_ID_3.equals(p.path("id").asText()))
                .findFirst().map(p -> p.path("status").asText()).orElse("");

        var response = rest.patch()
                .uri("/api/patterns/" + PENDING_ID_3)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("action", "defer"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);

        var after = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        var statusAfter = StreamSupport.stream(after.getBody().spliterator(), false)
                .filter(p -> PENDING_ID_3.equals(p.path("id").asText()))
                .findFirst().map(p -> p.path("status").asText()).orElse("");

        assertThat(statusAfter).isEqualTo(statusBefore);
    }

    @Test
    void deactivateSetStatusToRejected() {
        var response = rest.patch()
                .uri("/api/patterns/" + ACTIVE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("action", "deactivate"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(204);

        var patterns = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        var found = StreamSupport.stream(patterns.getBody().spliterator(), false)
                .filter(p -> ACTIVE_ID.equals(p.path("id").asText()))
                .findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().path("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void unknownActionReturns400() {
        var response = rest.patch()
                .uri("/api/patterns/" + PENDING_ID_1)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("action", "frobnicate"))
                .retrieve()
                .onStatus(status -> status.value() == 400, (req, res) -> {})
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
