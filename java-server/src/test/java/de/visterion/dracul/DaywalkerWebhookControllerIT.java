package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraIntraday;
import de.visterion.dracul.hunting.agora.IntradayCandles;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.daywalker.enabled=true",
        "dracul.daywalker.webhook-token=test-dw-token",
        "dracul.daywalker.session-cron=0 30 13 * * 1-5",
        "dracul.public-url=http://test.invalid:9090"
})
class DaywalkerWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired WatchlistRepository watchlist;
    @Autowired DaywalkerAlertRepository alerts;

    @MockitoBean AgoraIntraday intraday;
    @MockitoBean AgoraCompanyData companyData;
    @MockitoBean AgoraFilings filings;
    @MockitoBean TelegramNotifier telegramNotifier;
    @Autowired JdbcClient jdbc;

    RestClient rest;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM daywalker_alerts WHERE symbol IN ('SPK','CMP','CRT','INF')").update();
        jdbc.sql("DELETE FROM watchlist_items WHERE ticker IN ('SPK','CMP','CRT','INF')").update();
        when(telegramNotifier.notifyAlert(anyString(), anyString(), anyString(), any())).thenReturn(true);
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(intraday.candles(anyString())).thenReturn(new IntradayCandles(List.of(), List.of()));
        when(companyData.news(anyString(), any(), any())).thenReturn(List.of());
        when(companyData.recommendations(anyString())).thenReturn(List.of());
        when(filings.recentForm4(any(), any())).thenReturn(de.visterion.dracul.hunting.DataSourceResult.healthy("agora", List.of()));
    }

    @Test
    void eventsEndpointReturnsDetectedEvents() {
        watchlist.insert("default", "SPK", "Spike Co", 100.0, List.of(100.0), "", null, null);
        when(intraday.candles("SPK")).thenReturn(
                new IntradayCandles(List.of(new BigDecimal("100"), new BigDecimal("106")), List.of()));

        JsonNode resp = rest.post().uri("/api/daywalker/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("session_id", "s1", "agent", "daywalker",
                        "since", "2026-06-03T17:00:00Z", "now", "2026-06-03T18:00:00Z"))
                .retrieve().body(JsonNode.class);

        boolean found = false;
        for (JsonNode e : resp.path("events")) {
            if ("SPK".equals(e.path("symbol").asText())
                    && "PRICE_SPIKE".equals(e.path("trigger_type").asText())) found = true;
        }
        assertThat(found).as("price spike event for SPK").isTrue();
    }

    @Test
    void eventsEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/daywalker/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("now", "2026-06-03T18:00:00Z"))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsAlert() {
        watchlist.insert("default", "CMP", "Complete Co", 80.0, List.of(80.0), "", null, null);

        var resp = rest.post().uri("/api/daywalker/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .header("X-Vistierie-Run-Id", "run-dw-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-dw-1", "status", "done",
                        "output", Map.of("symbol", "CMP", "trigger_type", "PRICE_SPIKE",
                                "severity", "WARNING", "thesis", "Sharp move on no news.",
                                "confidence", 0.6)))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(alerts.lastAlertAt("default", "CMP", "PRICE_SPIKE")).isPresent();
    }

    @Test
    void completeEndpointAcknowledgesUnknownSymbolWithoutPersisting() {
        var resp = rest.post().uri("/api/daywalker/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .header("X-Vistierie-Run-Id", "run-dw-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-dw-2", "status", "done",
                        "output", Map.of("symbol", "GHOST", "trigger_type", "PRICE_SPIKE",
                                "severity", "INFO", "thesis", "x", "confidence", 0.3)))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(alerts.lastAlertAt("default", "GHOST", "PRICE_SPIKE")).isEmpty();
    }

    @Test
    void completeEndpointAcknowledgesNonSuccessStatusWithoutPersisting() {
        var resp = rest.post().uri("/api/daywalker/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .header("X-Vistierie-Run-Id", "run-dw-failed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-dw-failed", "status", "failed",
                        "error", "LLM timeout"))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/daywalker/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "done", "output", Map.of()))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void criticalCompletionPushesAndMarksNotificationSent() {
        watchlist.insert("default", "CRT", "Critical Co", 70.0, List.of(70.0), "", null, null);

        var resp = rest.post().uri("/api/daywalker/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .header("X-Vistierie-Run-Id", "run-crt-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-crt-1", "status", "done",
                        "output", Map.of("symbol", "CRT", "trigger_type", "INSIDER_SELL",
                                "severity", "CRITICAL", "thesis", "Cluster of insider sales.",
                                "confidence", 0.8)))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        verify(telegramNotifier).notifyAlert("CRT", "INSIDER_SELL", "CRITICAL", "Cluster of insider sales.");
        Boolean sent = jdbc.sql(
                "SELECT notification_sent FROM daywalker_alerts WHERE symbol = 'CRT'")
                .query(Boolean.class).single();
        assertThat(sent).isTrue();
    }

    @Test
    void infoCompletionDoesNotPush() {
        watchlist.insert("default", "INF", "Info Co", 40.0, List.of(40.0), "", null, null);

        rest.post().uri("/api/daywalker/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-dw-token")
                .header("X-Vistierie-Run-Id", "run-inf-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-inf-1", "status", "done",
                        "output", Map.of("symbol", "INF", "trigger_type", "NEGATIVE_NEWS",
                                "severity", "INFO", "thesis", "Routine headline.",
                                "confidence", 0.2)))
                .retrieve().toBodilessEntity();

        verify(telegramNotifier, never()).notifyAlert(anyString(), anyString(), anyString(), any());
        Boolean sent = jdbc.sql(
                "SELECT notification_sent FROM daywalker_alerts WHERE symbol = 'INF'")
                .query(Boolean.class).single();
        assertThat(sent).isFalse();
    }
}
