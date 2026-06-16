package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.wikipedia.Sp500Constituent;
import de.visterion.dracul.hunting.wikipedia.WikipediaSp500Adapter;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.index.webhook-token=test-index-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiIndexWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WikipediaSp500Adapter wikipedia;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
        when(wikipedia.recentConstituents()).thenReturn(DataSourceResult.healthy("wikipedia", List.of()));
    }

    @Test
    void toolEndpointReturnsCandidates() {
        when(wikipedia.recentConstituents()).thenReturn(DataSourceResult.healthy("wikipedia", List.of(
                new Sp500Constituent("NEWO", "NewCo", LocalDate.now().minusDays(5)))));

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_recent_index_additions",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        JsonNode cands = resp.path("output").path("candidates");
        boolean found = false;
        for (JsonNode c : cands) if ("NEWO".equals(c.path("symbol").asText())) found = true;
        assertThat(found).as("NEWO candidate returned").isTrue();
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsIndexPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "NEWO", "companyName", "NewCo",
                        "anomalyType", "INDEX_INCLUSION", "confidence", 0.66,
                        "thesis", "Added to S&P 500 last week; index-fund demand still building.",
                        "signals", List.of("Recent S&P 500 addition"),
                        "risks", List.of("Drift may already be priced"),
                        "horizon", "1m")));
        var resp = rest.post().uri("/api/strigoi-index/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .header("X-Vistierie-Run-Id", "run-index-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-index-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        boolean found = false;
        for (JsonNode p : chronicle.path("prey")) {
            if ("NEWO".equals(p.path("symbol").asText())
                    && "strigoi-index".equals(p.path("discoveredBy").asText())
                    && "INDEX_INCLUSION".equals(p.path("anomalyType").asText())) found = true;
        }
        assertThat(found).as("INDEX_INCLUSION prey persisted + visible").isTrue();
    }

    @Test
    void completeEndpointSkipsBlankSymbolPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "", "companyName", "BlankCo Index",
                        "anomalyType", "INDEX_INCLUSION", "confidence", 0.5,
                        "thesis", "No ticker.", "signals", List.of(), "risks", List.of(),
                        "horizon", "1m")));
        rest.post().uri("/api/strigoi-index/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .header("X-Vistierie-Run-Id", "run-index-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-index-2", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        for (JsonNode p : chronicle.path("prey")) {
            assertThat(p.path("companyName").asText()).isNotEqualTo("BlankCo Index");
        }
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-index/complete")
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
        org.mockito.Mockito.when(wikipedia.recentConstituents())
                .thenReturn(DataSourceResult.unavailable("wikipedia", "wikipedia: 503"));

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail", "tool_name", "fetch_recent_index_additions",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");

        // second identical call — must NOT be served from cache
        rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail-2", "tool_name", "fetch_recent_index_additions",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        org.mockito.Mockito.verify(wikipedia, org.mockito.Mockito.times(2))
                .recentConstituents();
    }
}
