package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.strigoi.echo.EarningsObservation;
import de.visterion.dracul.strigoi.echo.EarningsSourceRouter;
import de.visterion.dracul.strigoi.echo.EchoEnrichmentService;
import de.visterion.dracul.strigoi.echo.EnrichedPeadCandidate;
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

import java.math.BigDecimal;
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
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.echo.webhook-token=test-echo-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiEchoWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean EarningsSourceRouter earnings;
    @MockitoBean EchoEnrichmentService enrichment;

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
        var aapl = new EarningsObservation(
                "AAPL", "Apple Inc.", LocalDate.now().minusDays(2),
                new BigDecimal("1.65"), new BigDecimal("1.50"), new BigDecimal("10.0"),
                new BigDecimal("1000"), new BigDecimal("900"));
        when(earnings.recent(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("finnhub", List.of(aapl)));
        // Deterministic enrichment: one AAPL candidate, SUE unavailable, revenue-beat present.
        var enriched = new EnrichedPeadCandidate(
                "AAPL", "Apple Inc.", LocalDate.now().minusDays(2), 2,
                new BigDecimal("1.65"), new BigDecimal("1.50"), new BigDecimal("10.0"),
                null, null, false, false,
                new BigDecimal("11.111100"), true, null, new BigDecimal("190.00"));
        when(enrichment.enrich(any())).thenReturn(List.of(enriched));
    }

    @Test
    void toolEndpointReturns200WithEnrichedCandidate() {
        JsonNode resp = rest.post().uri("/api/strigoi-echo/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_recent_pead_candidates",
                            "input", Map.of("lookback_days", 7)))
                .retrieve().body(JsonNode.class);

        JsonNode c0 = resp.path("output").path("candidates").path(0);
        assertThat(c0.path("symbol").asText()).isEqualTo("AAPL");
        assertThat(c0.has("sueAvailable")).isTrue();
        assertThat(c0.has("revenueSurprisePercent")).isTrue();
        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("healthy");
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-echo/tools/fetch-candidates")
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
    void completeEndpointPersistsPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "ECHA", "companyName", "Echo A",
                       "anomalyType", "PEAD", "confidence", 0.7,
                       "thesis", "Strong positive earnings surprise.",
                       "signals", List.of("EPS beat by 18%"),
                       "risks", List.of("Soft forward guidance"),
                       "horizon", "3m")
        ));
        var resp = rest.post().uri("/api/strigoi-echo/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .header("X-Vistierie-Run-Id", "run-echo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-echo-1", "status", "done",
                            "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        JsonNode preyArr = chronicle.path("prey");
        boolean found = false;
        for (JsonNode p : preyArr) {
            if ("ECHA".equals(p.path("symbol").asText())
                    && "strigoi-echo".equals(p.path("discoveredBy").asText())
                    && "PEAD".equals(p.path("anomalyType").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("inserted PEAD prey visible in chronicle").isTrue();
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-echo/complete")
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
        var resp = rest.post().uri("/api/strigoi-echo/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .header("X-Vistierie-Run-Id", "run-failed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-failed", "status", "failed",
                            "error", "LLM timeout"))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void unavailableSourceSurfacesAndIsNotCached() {
        org.mockito.Mockito.when(earnings.recent(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DataSourceResult.unavailable("finnhub", "finnhub: 503"));

        JsonNode resp = rest.post().uri("/api/strigoi-echo/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail", "tool_name", "fetch_recent_pead_candidates",
                        "input", Map.of("lookback_days", 7)))
                .retrieve().body(JsonNode.class);

        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");

        // second identical call — must NOT be served from cache
        rest.post().uri("/api/strigoi-echo/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail-2", "tool_name", "fetch_recent_pead_candidates",
                        "input", Map.of("lookback_days", 7)))
                .retrieve().body(JsonNode.class);

        org.mockito.Mockito.verify(earnings, org.mockito.Mockito.times(2))
                .recent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
