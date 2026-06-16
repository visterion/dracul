package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.edgar.EdgarFormFourAdapter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
import static org.mockito.Mockito.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.insider.enabled=true",
        "dracul.strigoi.insider.webhook-token=test-token-abc",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiInsiderWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean EdgarFormFourAdapter edgar;

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
        when(edgar.recentFilings(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("edgar", List.of()));
    }

    @Test
    void toolEndpointReturns200WithValidBearer() {
        var resp = rest.post().uri("/api/strigoi-insider/tools/fetch-clusters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_recent_clusters",
                            "input", Map.of("lookback_days", 7)))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-insider/tools/fetch-clusters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("run_id", "r1", "tool_name", "x", "input", Map.of()))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void unavailableSourceSurfacesAndIsNotCached() {
        when(edgar.recentFilings(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.unavailable("edgar", "edgar: 503"));

        for (int i = 0; i < 2; i++) {
            JsonNode resp = rest.post().uri("/api/strigoi-insider/tools/fetch-clusters")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-abc")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("run_id", "r-unavail-" + i, "tool_name", "fetch_recent_clusters",
                                "input", Map.of("lookback_days", 7)))
                    .retrieve().body(JsonNode.class);
            assertThat(resp.path("output").path("data_source_health").path("status").asText())
                    .isEqualTo("unavailable");
        }

        verify(edgar, times(2)).recentFilings(any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void completeEndpointPersistsPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "TSTA", "companyName", "Test A",
                       "anomalyType", "INSIDER_CLUSTER", "confidence", 0.7,
                       "thesis", "Strong insider cluster.",
                       "signals", List.of("3 directors bought"),
                       "risks", List.of("Stock down YTD"),
                       "horizon", "3m")
        ));
        var resp = rest.post().uri("/api/strigoi-insider/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-abc")
                .header("X-Vistierie-Run-Id", "run-it-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-it-1", "status", "done",
                            "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        JsonNode preyArr = chronicle.path("prey");
        boolean found = false;
        for (JsonNode p : preyArr) {
            if ("TSTA".equals(p.path("symbol").asText())
                    && "strigoi-insider".equals(p.path("discoveredBy").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("inserted prey visible in chronicle").isTrue();
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-insider/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("run_id", "x", "status", "done",
                                "output", Map.of("prey", List.of())))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointAcknowledgesNonSuccessStatus() {
        var resp = rest.post().uri("/api/strigoi-insider/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-abc")
                .header("X-Vistierie-Run-Id", "run-failed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-failed", "status", "failed",
                            "error", "LLM timeout"))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
