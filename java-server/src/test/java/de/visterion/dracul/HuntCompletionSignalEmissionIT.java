package de.visterion.dracul;

import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.strigoi.spin.EnrichedSpinCandidate;
import de.visterion.dracul.strigoi.spin.SpinEnrichmentService;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** Proves the hunt /complete path feeds the executor when it is enabled:
 *  persisted prey become pending executor signals. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.spin.enabled=true",
        "dracul.strigoi.spin.webhook-token=test-spin-token",
        "dracul.executor.enabled=true",
        "dracul.public-url=http://test.invalid:9090"
})
class HuntCompletionSignalEmissionIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired ExecutorSignalRepository signalRepo;
    @MockitoBean AgoraFilings filings;
    @MockitoBean SpinEnrichmentService enrichment;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(filings.searchSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(enrichment.enrich(any())).thenReturn(List.of(new EnrichedSpinCandidate(
                "SPN", "SpinCo Inc", "10-12B", "2026-05-20", "http://sec/s1", "SUMMARY", true)));
    }

    @Test
    void completePathEmitsExecutorSignalFromPrey() {
        String symbol = "EMITIT" + System.nanoTime();
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", symbol, "companyName", "Emit Co",
                        "anomalyType", "SPINOFF", "confidence", 0.71,
                        "thesis", "Forced index selling post-separation.",
                        "signals", List.of("Dropped from parent's index"),
                        "risks", List.of("Thin float"),
                        "horizon", "6m")));

        rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", "run-emit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-emit-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        assertThat(signalRepo.findPending(Integer.MAX_VALUE)).anySatisfy(s -> {
            assertThat(s.symbol()).isEqualTo(symbol);
            assertThat(s.direction()).isEqualTo("BUY");
            assertThat(s.mechanism()).isEqualTo("SPINOFF");
            assertThat(s.source()).isEqualTo("strigoi-spin");
            assertThat(s.confidence()).isEqualTo(0.71);
        });
    }
}
