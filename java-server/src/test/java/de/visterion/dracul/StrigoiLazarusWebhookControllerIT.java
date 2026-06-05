package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.hunting.finnhub.BasicFinancials;
import de.visterion.dracul.hunting.finnhub.FinnhubFundamentalsAdapter;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.lazarus.webhook-token=test-lazarus-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiLazarusWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired WatchlistRepository watchlist;
    @MockitoBean FinnhubFundamentalsAdapter fundamentals;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
        when(fundamentals.basicFinancials(anyString())).thenReturn(null);
    }

    @Test
    void toolEndpointReturnsCandidatesNearLow() {
        watchlist.insert("default", "ACME", "Acme Inc", 10.50, List.of(), "lazarus-it", null);
        when(fundamentals.basicFinancials("ACME")).thenReturn(new BasicFinancials(
                10.0, 40.0, 5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3));

        JsonNode resp = rest.post().uri("/api/strigoi-lazarus/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-lazarus-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_quality_at_low_candidates",
                        "input", Map.of()))
                .retrieve().body(JsonNode.class);

        JsonNode cands = resp.path("output").path("candidates");
        boolean found = false;
        for (JsonNode c : cands) if ("ACME".equals(c.path("symbol").asText())) found = true;
        assertThat(found).as("ACME candidate returned").isTrue();
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-lazarus/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsQualityPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "LZRS", "companyName", "Lazarus Co",
                        "anomalyType", "QUALITY_52W_LOW", "confidence", 0.72,
                        "thesis", "Healthy balance sheet at a 52-week low; F-Score 8.",
                        "signals", List.of("Positive FCF", "Low leverage"),
                        "risks", List.of("Sector headwind"),
                        "horizon", "12m")));
        var resp = rest.post().uri("/api/strigoi-lazarus/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-lazarus-token")
                .header("X-Vistierie-Run-Id", "run-lazarus-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-lazarus-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        boolean found = false;
        for (JsonNode p : chronicle.path("prey")) {
            if ("LZRS".equals(p.path("symbol").asText())
                    && "strigoi-lazarus".equals(p.path("discoveredBy").asText())
                    && "QUALITY_52W_LOW".equals(p.path("anomalyType").asText())) found = true;
        }
        assertThat(found).as("QUALITY_52W_LOW prey persisted + visible").isTrue();
    }

    @Test
    void completeEndpointSkipsBlankSymbolPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "", "companyName", "BlankCo Lazarus",
                        "anomalyType", "QUALITY_52W_LOW", "confidence", 0.6,
                        "thesis", "No ticker.", "signals", List.of(), "risks", List.of(),
                        "horizon", "12m")));
        rest.post().uri("/api/strigoi-lazarus/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-lazarus-token")
                .header("X-Vistierie-Run-Id", "run-lazarus-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-lazarus-2", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        for (JsonNode p : chronicle.path("prey")) {
            assertThat(p.path("companyName").asText()).isNotEqualTo("BlankCo Lazarus");
        }
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-lazarus/complete")
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
}
