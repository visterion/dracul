package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.voievod.enabled=true",
        "dracul.voievod.webhook-token=test-voievod-token",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.voievod.schedule=0 0 8 * * 1-5"
})
class VoievodWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired PreyRepository preyRepo;
    @Autowired VerdictRepository verdictRepo;
    @Autowired JdbcClient jdbc;
    @MockitoBean AgoraMarketData marketData;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(marketData.resolve(anyString()))
                .thenReturn(new MarketData("Resolved Co", new BigDecimal("123.45"), List.of()));
    }

    private void seedPrey(String symbol, String discoveredBy, double confidence) {
        String now = Instant.now().toString();
        preyRepo.insertAll(List.of(new Prey(
                UUID.randomUUID().toString(), symbol, symbol + " Corp", "ANOM",
                confidence, "thesis for " + discoveredBy,
                List.of("signal-" + discoveredBy), List.of("risk-" + discoveredBy),
                "6m", discoveredBy, now)));
    }

    private void complete(String runId, String symbol, String summary) {
        rest.post().uri("/api/voievod/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-voievod-token")
                .header("X-Vistierie-Run-Id", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", runId, "status", "done",
                        "output", Map.of("verdicts", List.of(Map.of("symbol", symbol, "summary", summary)))))
                .retrieve().toBodilessEntity();
    }

    @Test
    void toolReturnsClusterForTwoStrigoiSameSymbol() {
        seedPrey("CLU", "strigoi-spin", 0.7);
        seedPrey("CLU", "strigoi-insider", 0.6);

        JsonNode resp = rest.post().uri("/api/voievod/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-voievod-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("input", Map.of()))
                .retrieve().body(JsonNode.class);

        boolean found = false;
        for (JsonNode c : resp.path("output").path("clusters")) {
            if ("CLU".equals(c.path("symbol").asText())) {
                found = true;
                assertThat(c.path("prey")).hasSize(2);
            }
        }
        assertThat(found).as("CLU cluster returned").isTrue();
    }

    @Test
    void completePersistsVerdictWithDerivedFields() {
        seedPrey("VRD", "strigoi-spin", 0.7);
        seedPrey("VRD", "strigoi-insider", 0.6);

        complete("run-vrd-1", "VRD", "Two strigoi corroborate VRD.");

        var detail = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> "VRD".equals(v.symbol())).findFirst().orElseThrow();
        assertThat(detail.consensusScore()).isEqualTo(0.88);
        assertThat(detail.contributingStrigoi()).containsExactlyInAnyOrder("strigoi-spin", "strigoi-insider");
        assertThat(detail.summary()).isEqualTo("Two strigoi corroborate VRD.");

        var full = verdictRepo.findDetailById(detail.id()).orElseThrow();
        assertThat(full.avgConfidence()).isEqualTo(0.65);
        assertThat(full.currentPrice()).isEqualTo(123.45);
        assertThat(full.contributingDetails()).hasSize(2);
    }

    @Test
    void rerunWithSamePreyDoesNotDuplicate() {
        seedPrey("DUP", "strigoi-spin", 0.7);
        seedPrey("DUP", "strigoi-insider", 0.6);
        complete("run-dup-1", "DUP", "first");
        complete("run-dup-2", "DUP", "second");
        long count = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> "DUP".equals(v.symbol())).count();
        assertThat(count).isEqualTo(1);
        var detail = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> "DUP".equals(v.symbol())).findFirst().orElseThrow();
        assertThat(detail.summary()).isEqualTo("first");
    }

    @Test
    void decidedVerdictIsNotOverwritten() {
        seedPrey("DEC", "strigoi-spin", 0.7);
        seedPrey("DEC", "strigoi-insider", 0.6);
        complete("run-dec-1", "DEC", "original summary");
        var v = verdictRepo.findAllByUser("default", true).stream()
                .filter(x -> "DEC".equals(x.symbol())).findFirst().orElseThrow();
        verdictRepo.updateDecision(v.id(), "DISMISS");

        complete("run-dec-2", "DEC", "rewritten summary");

        var after = verdictRepo.findDetailById(v.id()).orElseThrow();
        assertThat(after.summary()).isEqualTo("original summary");
        assertThat(after.decision()).isEqualTo("DISMISS");
    }

    @Test
    void emittedSymbolThatNoLongerQualifiesIsSkipped() {
        seedPrey("SOLO", "strigoi-spin", 0.7);
        complete("run-solo-1", "SOLO", "should not persist");
        long count = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> "SOLO".equals(v.symbol())).count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void blankSymbolIsSkipped() {
        complete("run-blank-1", "", "blank");
        long count = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> v.symbol() == null || v.symbol().isBlank()).count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void priceFailureStillPersistsWithNullPrice() {
        when(marketData.resolve(anyString()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom")); // MarketDataException requires (Kind, message)
        seedPrey("NOPX", "strigoi-spin", 0.7);
        seedPrey("NOPX", "strigoi-insider", 0.6);
        complete("run-nopx-1", "NOPX", "price unavailable but still consolidated");
        var detail = verdictRepo.findAllByUser("default", true).stream()
                .filter(v -> "NOPX".equals(v.symbol())).findFirst().orElseThrow();
        var full = verdictRepo.findDetailById(detail.id()).orElseThrow();
        assertThat(full.currentPrice()).isEqualTo(0.0);
        // graceful degradation stores SQL NULL (the double read-projection surfaces it as 0.0)
        Integer nonNull = jdbc.sql("SELECT count(*) FROM verdicts WHERE symbol = 'NOPX' AND current_price IS NOT NULL")
                .query(Integer.class).single();
        assertThat(nonNull).isZero();
    }

    @Test
    void toolReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/voievod/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/voievod/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "done", "output", Map.of("verdicts", List.of())))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }
}
