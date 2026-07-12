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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        // The strigoi AgentDefaultProvider beans are @ConditionalOnProperty and default
        // to disabled, so /api/settings/budgets only lists agents when they are enabled.
        // Declare them here (mirrors SettingsControllerRosterTest) so the "agents hasSize 6"
        // assertion is deterministic instead of depending on a cached context created by
        // some other IT that happened to enable them.
        "dracul.strigoi.spin.enabled=true", "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.insider.enabled=true", "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SettingsBudgetControllerIT {

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
    void getBudgetsReturns200WithTenantAndAgents() {
        var response = rest.get().uri("/api/settings/budgets")
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("tenant")).isTrue();
        assertThat(body.has("agents")).isTrue();
        assertThat(body.get("agents").isArray()).isTrue();
        assertThat(body.get("agents")).hasSize(6);
        // MockVistierieClient.getTenantBudget() returns dailyCapMicros = 5_000_000
        assertThat(body.get("tenant").get("dailyCapMicros").asLong()).isEqualTo(5_000_000L);
    }

    @Test
    void patchTenantBudgetReturns200WithUpdatedValues() {
        var response = rest.patch().uri("/api/settings/budgets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of(
                        "dailyCapMicros", 3_000_000L,
                        "monthlyCapMicros", 90_000_000L,
                        "dailyWarnPercent", 70,
                        "monthlyWarnPercent", 75
                ))
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        // MockVistierieClient.patchTenantBudget returns the patched values
        assertThat(body.get("dailyCapMicros").asLong()).isEqualTo(3_000_000L);
    }

    @Test
    void patchAgentBudgetReturns200() {
        var response = rest.patch().uri("/api/settings/budgets/agents/strigoi-spin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of(
                        "dailyCapMicros", 2_000_000L,
                        "monthlyCapMicros", 50_000_000L
                ))
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
    }
}
