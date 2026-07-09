package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class DecisionLogRepositoryTest {

    @Autowired DecisionLogRepository repo;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @Test
    void insertRichAndReadBack() throws Exception {
        String symbol = "DLOG-" + UUID.randomUUID();
        var vetoResults = mapper.readTree(
                "[{\"check\":\"STOP_BREACH\",\"passed\":false,\"measured\":\"close 94.0 < stop 95.0\"}]");
        var inputsSnapshot = mapper.readTree("{\"atr22\":1.5}");
        var latency = mapper.readTree("{\"trigger_to_order_seconds\":12}");

        var d = new DecisionLog(
                null, "run-1", "exec-v0.2", "HARD_TRIGGER", "sig-1", "strigoi-spin", "v1",
                symbol, inputsSnapshot, vetoResults, "LOG_HARD_EXIT", "HARD_STOP",
                null, "close breached hard stop", 0.9, latency, null);
        repo.insert(d);

        var recent = repo.findRecent(50);
        assertThat(recent).anySatisfy(r -> {
            assertThat(r.symbol()).isEqualTo(symbol);
            assertThat(r.ruleVersion()).isEqualTo("exec-v0.2");
            assertThat(r.action()).isEqualTo("LOG_HARD_EXIT");
            assertThat(r.reasonCode()).isEqualTo("HARD_STOP");
            assertThat(r.latency().path("trigger_to_order_seconds").asInt()).isEqualTo(12);
            assertThat(r.inputsSnapshot().path("atr22").asDouble()).isEqualTo(1.5);
            assertThat(r.vetoResults().get(0).path("check").asString()).isEqualTo("STOP_BREACH");
        });
    }

    @Test
    void nullableJsonAndConfidence() {
        String symbol = "DLOG-" + UUID.randomUUID();
        var d = new DecisionLog(
                null, "run-2", "exec-v0.2", "SOFT_TRIGGER", "sig-2", "strigoi-spin", "v1",
                symbol, null, null, "SKIP", null,
                null, null, null, null, null);
        repo.insert(d);

        var recent = repo.findRecent(50);
        assertThat(recent).anySatisfy(r -> {
            assertThat(r.symbol()).isEqualTo(symbol);
            assertThat(r.orderJson()).isNull();
            assertThat(r.latency()).isNull();
            assertThat(r.confidenceInDecision()).isNull();
            assertThat(r.inputsSnapshot()).isNotNull();
            assertThat(r.vetoResults()).isNotNull();
        });
    }
}
