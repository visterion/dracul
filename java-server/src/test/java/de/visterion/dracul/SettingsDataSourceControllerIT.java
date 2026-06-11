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
class SettingsDataSourceControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
    }

    private JsonNode row(JsonNode arr, String id) {
        return StreamSupport.stream(arr.spliterator(), false)
                .filter(n -> n.get("id").asText().equals(id)).findFirst().orElseThrow();
    }

    @Test void getDataSourcesReturnsFourSources() {
        var body = rest.get().uri("/api/settings/data-sources").retrieve().body(JsonNode.class);
        assertThat(body).isNotNull();
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(4);
        assertThat(row(body, "yahoo").get("status").asText()).isEqualTo("rate_limited");
        assertThat(row(body, "edgar").get("usedBy").isArray()).isTrue();
    }

    @Test void cacheHoldsUnlessRefresh() {
        var first = rest.get().uri("/api/settings/data-sources").retrieve().body(JsonNode.class);
        var second = rest.get().uri("/api/settings/data-sources").retrieve().body(JsonNode.class);
        assertThat(second.get(0).get("checkedAt").asText()).isEqualTo(first.get(0).get("checkedAt").asText());
        var refreshed = rest.get().uri("/api/settings/data-sources?refresh=true").retrieve().body(JsonNode.class);
        assertThat(refreshed.get(0).get("checkedAt").asText()).isNotEqualTo(first.get(0).get("checkedAt").asText());
    }
}
