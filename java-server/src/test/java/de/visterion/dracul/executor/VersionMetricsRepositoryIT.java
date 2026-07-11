package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.outcome.OutcomeLogRepository;
import de.visterion.dracul.outcome.OutcomeLogRow;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Runs the real Task-5 aggregate query (join cast, GROUP BY, ordering) against a real Postgres
 *  instance seeded per the V23 {@code outcome_log} schema, verifying the SQL actually executes
 *  and groups/aggregates correctly — the mocked-repository {@link VersionMetricsServiceTest}
 *  only covers the gate math. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VersionMetricsRepositoryIT {

    @Autowired VersionMetricsRepository versionMetricsRepo;
    @Autowired OutcomeLogRepository outcomeLogRepo;
    @Autowired DecisionLogRepository decisionLogRepo;
    @Autowired ObjectMapper mapper;

    private JsonNode empty(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void seedDecisionAndOutcome(String logId, String sourceAgent,
            String agentVersion, String ruleVersion, String realizedR) {
        var decision = new DecisionLog(
                logId, "run-1", ruleVersion, "SIGNAL", "sig-" + logId, sourceAgent, agentVersion,
                "ACME", empty("{}"), empty("[]"), "ENTER", null, null, "reasoning", 0.7, null, null);
        decisionLogRepo.insert(decision);

        var outcome = new OutcomeLogRow(
                "TRADE", logId, null, "ACME", null, true, new BigDecimal("100"), new BigDecimal("0"),
                10, new BigDecimal("1.0"), new BigDecimal("-0.5"),
                realizedR == null ? null : new BigDecimal(realizedR), "STOP",
                null, empty("[]"), false, false, empty("{}"), true, sourceAgent, agentVersion,
                ruleVersion, true);
        outcomeLogRepo.upsert(outcome);
    }

    @Test
    @Order(1)
    void emptyDbYieldsEmptyList() {
        assertThat(versionMetricsRepo.findGroupedByVersion()).isEmpty();
    }

    @Test
    @Order(2)
    void groupsByAgentVersionRuleVersionAndAggregatesRealizedR() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();
        String idOther = UUID.randomUUID().toString();

        seedDecisionAndOutcome(idA, "gropar", "1", "v3", "1.0");
        seedDecisionAndOutcome(idB, "gropar", "1", "v3", "-0.5");
        seedDecisionAndOutcome(idOther, "gropar", "2", "v4", "0.2");

        List<VersionMetricsRepository.Row> rows = versionMetricsRepo.findGroupedByVersion();

        assertThat(rows).hasSize(2);
        VersionMetricsRepository.Row groupV3 = rows.stream()
                .filter(r -> r.ruleVersion().equals("v3")).findFirst().orElseThrow();
        assertThat(groupV3.agent()).isEqualTo("gropar");
        assertThat(groupV3.agentVersion()).isEqualTo("1");
        assertThat(groupV3.decisions()).isEqualTo(2);
        assertThat(groupV3.avgReturn()).isCloseTo(0.25, offset(1e-9));
        assertThat(groupV3.hitRate()).isCloseTo(0.5, offset(1e-9));
        assertThat(groupV3.firstAt()).isNotNull();
        assertThat(groupV3.lastAt()).isNotNull();

        VersionMetricsRepository.Row groupV4 = rows.stream()
                .filter(r -> r.ruleVersion().equals("v4")).findFirst().orElseThrow();
        assertThat(groupV4.decisions()).isEqualTo(1);
        assertThat(groupV4.hitRate()).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    @Order(3)
    void completeRowsWithNullRealizedRAreExcluded() {
        // complete=true does NOT imply realized_r is set — completeness comes from the reentry
        // window elapsing, so a null-R row must not count toward decisions or drag hit_rate down.
        String idWin = UUID.randomUUID().toString();
        String idNullR = UUID.randomUUID().toString();

        seedDecisionAndOutcome(idWin, "voievod", "3", "v7", "0.8");
        seedDecisionAndOutcome(idNullR, "voievod", "3", "v7", null);

        List<VersionMetricsRepository.Row> rows = versionMetricsRepo.findGroupedByVersion().stream()
                .filter(r -> r.agent().equals("voievod")).toList();

        assertThat(rows).hasSize(1);
        VersionMetricsRepository.Row group = rows.get(0);
        assertThat(group.decisions()).isEqualTo(1);
        assertThat(group.avgReturn()).isCloseTo(0.8, offset(1e-9));
        assertThat(group.hitRate()).isCloseTo(1.0, offset(1e-9));
    }
}
