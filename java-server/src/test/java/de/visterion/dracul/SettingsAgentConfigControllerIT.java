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
class SettingsAgentConfigControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
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

    private JsonNode row(JsonNode arr, String name) {
        return StreamSupport.stream(arr.spliterator(), false)
                .filter(n -> n.get("name").asText().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void getAgentsReturnsRowsWithConfig() {
        var body = rest.get().uri("/api/settings/agents")
                .retrieve().body(JsonNode.class);

        assertThat(body).isNotNull();
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(6);

        var spin = row(body, "strigoi-spin");
        assertThat(spin.get("tier").asText()).isEqualTo("Reasoning");
        assertThat(spin.get("schedule").asText()).isEqualTo("0 22 * * 1-5");
        assertThat(spin.get("primaryProvider").asText()).isEqualTo("anthropic");
        assertThat(spin.get("paused").asBoolean()).isFalse();

        var lazarus = row(body, "strigoi-lazarus");
        assertThat(lazarus.get("state").asText()).isEqualTo("paused");
        assertThat(lazarus.get("paused").asBoolean()).isTrue();
    }

    @Test
    void patchAgentPausedReturnsUpdatedRow() {
        var body = rest.patch().uri("/api/settings/agents/strigoi-spin")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("paused", true))
                .retrieve().body(JsonNode.class);

        assertThat(body).isNotNull();
        assertThat(body.get("name").asText()).isEqualTo("strigoi-spin");
        assertThat(body.get("paused").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("paused");
    }
}
