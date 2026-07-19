package de.visterion.dracul;

import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.report.MorningReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class MorningReportScopeIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @MockitoBean HeldPositionService heldPositions;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        // MorningReportService.build is depot-driven: it projects heldPositionService.openPositions
        // (a single-account depot), not per-user watchlist rows. Seed one depot position for MRA.
        when(heldPositions.openPositions(anyString())).thenReturn(List.of(new HeldPosition(
                "MRA", BigDecimal.TEN, new BigDecimal("90"), new BigDecimal("900"),
                BigDecimal.ONE, "USD", null, null, null, null,
                new BigDecimal("80"), new BigDecimal("95"), "manual", "2026-07-01T00:00:00Z")));
    }

    @Test
    void reportReflectsDepotPositions() {
        // The report is single-account (depot-driven), so any authenticated user sees the same
        // depot positions -- there is no per-user position scoping left to test.
        MorningReport r = rest.get().uri("/api/morning-report")
                .header("X-Dev-User", "alice@x.com")
                .retrieve().body(MorningReport.class);

        assertThat(r.positions()).extracting("symbol").contains("MRA");
    }
}
