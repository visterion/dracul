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
class PatternControllerIT {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper objectMapper;

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
    void patternsReturns17SeedItems() {
        var response = rest.get()
                .uri("/api/patterns")
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Count only the V2 seed rows (ids prefixed c0000000-): other ITs sharing the
        // reused container (PatternGateRepositoryIT here, the Task 7 scorer fixtures,
        // the Task 8 controller ITs) insert throwaway patterns for user 'default' that
        // would otherwise inflate a whole-list count.
        long seedRows = 0;
        for (JsonNode item : response.getBody()) {
            if (item.get("id").asText().startsWith("c0000000-")) seedRows++;
        }
        assertThat(seedRows).isEqualTo(17);
    }
}
