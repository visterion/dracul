package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.edgar.EdgarSpinoffAdapter;
import de.visterion.dracul.hunting.edgar.SpinoffFiling;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.spin.enabled=true",
        "dracul.strigoi.spin.webhook-token=test-spin-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiSpinWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @MockitoBean EdgarSpinoffAdapter edgarSpinoff;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(edgarSpinoff.recentSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("edgar", List.of()));
    }

    @Test
    void toolEndpointReturnsCandidates() {
        when(edgarSpinoff.recentSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("edgar", List.of(
                        new SpinoffFiling("SPN", "Acme Spinco Inc", "10-12B", LocalDate.of(2026, 5, 20), "http://sec/u1"))));

        JsonNode resp = rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_recent_spinoff_candidates",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        JsonNode cands = resp.path("output").path("candidates");
        boolean found = false;
        for (JsonNode c : cands) if ("SPN".equals(c.path("symbol").asText())) found = true;
        assertThat(found).as("SPN candidate returned").isTrue();

        JsonNode health = resp.path("output").path("data_source_health");
        org.assertj.core.api.Assertions.assertThat(health.path("status").asText()).isEqualTo("healthy");
        org.assertj.core.api.Assertions.assertThat(health.path("source").asText()).isEqualTo("edgar");
        org.assertj.core.api.Assertions.assertThat(health.has("checked_at")).isTrue();
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsSpinoffPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "SPNX", "companyName", "Spinco X",
                        "anomalyType", "SPINOFF", "confidence", 0.7,
                        "thesis", "Forced index selling post-separation.",
                        "signals", List.of("Dropped from parent's index"),
                        "risks", List.of("Thin float"),
                        "horizon", "6m")));
        var resp = rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", "run-spin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-spin-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        boolean found = false;
        for (JsonNode p : chronicle.path("prey")) {
            if ("SPNX".equals(p.path("symbol").asText())
                    && "strigoi-spin".equals(p.path("discoveredBy").asText())
                    && "SPINOFF".equals(p.path("anomalyType").asText())) found = true;
        }
        assertThat(found).as("SPINOFF prey persisted + visible").isTrue();
    }

    @Test
    void completeEndpointSkipsBlankSymbolPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "", "companyName", "BlankCo Spinco",
                        "anomalyType", "SPINOFF", "confidence", 0.6,
                        "thesis", "Not trading yet.", "signals", List.of(), "risks", List.of(),
                        "horizon", "6m")));
        rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", "run-spin-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-spin-2", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        for (JsonNode p : chronicle.path("prey")) {
            assertThat(p.path("companyName").asText()).isNotEqualTo("BlankCo Spinco");
        }
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-spin/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "done", "output", Map.of("prey", List.of())))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void unavailableSourceSurfacesAndIsNotCached() {
        org.mockito.Mockito.when(edgarSpinoff.recentSpinoffs(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DataSourceResult.unavailable("edgar", "edgar: 503"));

        JsonNode resp = rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail", "tool_name", "fetch_recent_spinoff_candidates",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");

        // second identical call — must NOT be served from cache
        rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail-2", "tool_name", "fetch_recent_spinoff_candidates",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        org.mockito.Mockito.verify(edgarSpinoff, org.mockito.Mockito.times(2))
                .recentSpinoffs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
