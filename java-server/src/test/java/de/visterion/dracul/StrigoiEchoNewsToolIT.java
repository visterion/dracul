package de.visterion.dracul;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** T-echo-news: das Detail-Tool liefert die vollen News EINES Symbols inkl. summary.
 *  Alle Fixtures synthetisch. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.echo.webhook-token=test-echo-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiEchoNewsToolIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired de.visterion.dracul.agent.ToolFetchCache cache;
    @MockitoBean AgoraCompanyData companyData;

    RestClient rest;

    @BeforeEach
    void setUp() {
        cache.clear();
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
        var item = new NewsHeadline(
                "SYNTHETIC beat headline", "SYNTHETIC summary with the numbers",
                "synthetic-source", "rss", Instant.parse("2026-01-02T00:00:00Z"),
                "https://example.com/1", "example.com", 0.7);
        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of(item)));
    }

    private JsonNode call(Map<String, Object> input) {
        return rest.post().uri("/api/strigoi-echo/tools/fetch-news")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-echo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_candidate_news", "input", input))
                .retrieve().body(JsonNode.class);
    }

    @Test
    void returnsFullItemsIncludingSummary() {
        JsonNode n0 = call(Map.of("symbol", "AAPL", "since", "2026-01-01"))
                .path("output").path("news").path(0);

        assertThat(n0.path("headline").asText()).isEqualTo("SYNTHETIC beat headline");
        assertThat(n0.path("summary").asText()).isEqualTo("SYNTHETIC summary with the numbers");
        assertThat(n0.path("source").asText()).isEqualTo("synthetic-source");
        assertThat(n0.path("credibility").asDouble()).isEqualTo(0.7);
        assertThat(n0.has("datetime")).isTrue();
    }

    @Test
    void wrapsResponseInTheSameOutputEnvelopeAsFetchCandidates() {
        JsonNode resp = call(Map.of("symbol", "AAPL"));
        assertThat(resp.has("output")).isTrue();
        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("healthy");
    }

    @Test
    void missingSinceFallsBackToThirtyDays() {
        call(Map.of("symbol", "AAPL"));
        org.mockito.Mockito.verify(companyData)
                .newsResult(eq("AAPL"), eq(LocalDate.now().minusDays(30)), eq(LocalDate.now()));
    }

    /** Real failure mode: {@link AgoraCompanyData#newsResult} reports an outage via
     *  {@code DataSourceResult.unavailable(...)} — its documented contract, unlike
     *  {@link AgoraCompanyData#news}, which never throws and swallows an outage into an
     *  empty list. Mocking a throw from {@code news()} would test a behaviour the real
     *  method cannot exhibit; this mocks the health-aware method returning exactly the
     *  outage shape it is specified to return. */
    @Test
    void agoraFailureDegradesToEmptyListNotAnError() {
        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: boom"));

        JsonNode resp = call(Map.of("symbol", "AAPL"));

        assertThat(resp.path("output").path("news")).isEmpty();
        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");
    }

    /** The whole point of the health-aware variant: a genuinely newsless-but-healthy symbol
     *  must NOT be confused with an outage — both look like "empty list" at the {@code news}
     *  level, but only one of them is {@code status = "healthy"}. */
    @Test
    void newslessSymbolStaysHealthy() {
        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        JsonNode resp = call(Map.of("symbol", "QUIET"));

        assertThat(resp.path("output").path("news")).isEmpty();
        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("healthy");
    }

    /** An unavailable result must not be cached, so the very next call retries against
     *  Agora instead of replaying the stale outage.
     *  Caveat: {@code fetch_candidate_news} is not yet registered in {@code AgentToolCatalog}
     *  (Task 3), so {@code ToolFetchCache.resolveTtlNanos} currently returns 0 for it and
     *  NOTHING is cached regardless of health. This test therefore only proves the
     *  end-to-end behaviour — an unavailable call followed by a fresh call sees fresh data —
     *  not that the {@code healthyPayload} predicate is the reason. Once the tool is
     *  registered as cacheable, this should be re-checked to confirm it still passes when
     *  caching is actually live. */
    @Test
    void unavailableResultIsNotCached() {
        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: boom"));
        call(Map.of("symbol", "AAPL"));

        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        JsonNode resp = call(Map.of("symbol", "AAPL"));

        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("healthy");
        org.mockito.Mockito.verify(companyData, org.mockito.Mockito.times(2))
                .newsResult(eq("AAPL"), any(), any());
    }

    @Test
    void blankSymbolIsRejectedWith400() {
        assertThatThrownBy(() -> call(Map.of("symbol", "  ")))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void returns401WithoutBearer() {
        assertThatThrownBy(() ->
                rest.post().uri("/api/strigoi-echo/tools/fetch-news")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("input", Map.of("symbol", "AAPL")))
                        .retrieve().body(JsonNode.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }
}
