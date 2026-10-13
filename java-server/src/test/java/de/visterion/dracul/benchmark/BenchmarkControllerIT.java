package de.visterion.dracul.benchmark;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class BenchmarkControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired BenchmarkOutcomeRepository repo;
    @MockitoBean DepotBenchmarkService depot;

    @Test
    void summaryServesTheMeasuredAgent() {
        String agent = "strigoi-synbench" + Long.toString(System.nanoTime(), 36);
        repo.insert(new BenchmarkOutcome(EventKind.SIGNAL, agent, "SYNA", LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 1), Instant.parse("2026-03-01T22:00:00Z"), null, 20, LocalDate.of(2026, 3, 2),
                new BigDecimal("10"), LocalDate.of(2026, 3, 30), new BigDecimal("11"), "USD", new BigDecimal("0.9"),
                new BigDecimal("0.9"), new BigDecimal("0.1"), new BigDecimal("0.02"), new BigDecimal("0.08"),
                ExitKind.NORMAL));

        JsonNode resp = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build()
                .get().uri("/api/benchmark/summary").retrieve().body(JsonNode.class);

        assertThat(resp.path("benchmark").asString()).isEqualTo("EUNL.DE");
        assertThat(resp.path("decisionDate").asString()).isEqualTo("2027-04-13");
        assertThat(resp.path("depot").isNull()).isTrue();
        JsonNode row = null;
        for (JsonNode a : resp.path("agents")) if (agent.equals(a.path("agent").asString())) row = a;
        assertThat(row).isNotNull();
        assertThat(row.path("signal20").path("n").asInt()).isEqualTo(1);
        assertThat(row.path("signal20").path("mean").decimalValue()).isEqualByComparingTo("0.08");
        assertThat(row.path("signal60").path("n").asInt()).isZero();
        assertThat(row.path("paired20").isNull()).isTrue();
        assertThat(row.path("status").asString()).isEqualTo("INSUFFICIENT");
    }
}
