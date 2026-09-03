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
    void findBySignalIdReturnsAllActionsForThatSignalOldestFirst() {
        String signalId = "sig-" + UUID.randomUUID();

        var enter = new DecisionLog(
                null, "run-enter", "exec-v0.2", "SIGNAL", signalId, "strigoi-spin", "v1",
                "ACME", null, null, "ENTER", "OK",
                null, "opened on spin-off drift", 0.8, null, null);
        repo.insert(enter);

        var trim = new DecisionLog(
                null, "run-trim", "exec-v0.2", "SOFT_TRIGGER", signalId, "strigoi-spin", "v1",
                "ACME", null, null, "TRIM", "T2_TARGET",
                null, "trimmed at T2 target", 0.6, null, null);
        repo.insert(trim);

        var moves = repo.findBySignalId(signalId);

        assertThat(moves).hasSize(2);
        assertThat(moves).extracting(DecisionLog::action).containsExactly("ENTER", "TRIM");
        assertThat(moves).extracting(DecisionLog::runId).containsExactly("run-enter", "run-trim");
        assertThat(moves).allSatisfy(d -> assertThat(d.signalId()).isEqualTo(signalId));
    }

    /**
     * A tranche-2 add writes a decision_log row under the SAME signal_id as the entry it adds to,
     * and {@code findBySignalIdAndAction} takes the NEWEST match (ORDER BY created_at DESC LIMIT
     * 1). Written as {@code ENTER} that row would shadow the entry row for every consumer that
     * resolves "the ENTER row of this signal" — {@code outcome_log.log_id_ref}, both Brier scores,
     * the stop-basis table, the depot "why" — and hand them the nulls a tranche-2 row carries by
     * design. {@code ADD_TRANCHE} keeps the two apart.
     *
     * <p>Mutation: write the add-tranche row with {@code action = "ENTER"} (here, or in
     * {@code ExecutorWebhookController.logAddTrancheDecision}, whose action string
     * {@code addTrancheWritesADecisionLogRowWithTheSameOrderJson} pins).
     */
    @Test
    void addTrancheRowDoesNotShadowTheEntryRow() {
        String signalId = "sig-" + UUID.randomUUID();

        var enter = new DecisionLog(
                null, "run-enter", "exec-v0.5", "SIGNAL", signalId, "strigoi-spin", "v1",
                "ACME", null, null, "ENTER", null,
                null, "opened on spin-off drift", 0.8, null, null);
        repo.insert(enter);

        // The tranche-2 row as logAddTrancheDecision writes it: same signal, written later, with
        // null confidence / reasoning / agent version — a tranche 2 has no signal of its own.
        var addTranche = new DecisionLog(
                null, "run-t2", "exec-v0.5", "SIGNAL", signalId, "strigoi-spin", null,
                "ACME", null, null, "ADD_TRANCHE", null,
                null, null, null, null, null);
        repo.insert(addTranche);

        var resolved = repo.findBySignalIdAndAction(signalId, "ENTER");

        assertThat(resolved).isNotNull();
        assertThat(resolved.runId()).isEqualTo("run-enter");
        assertThat(resolved.reasoning()).isEqualTo("opened on spin-off drift");
        assertThat(resolved.confidenceInDecision()).isEqualTo(0.8);
        assertThat(resolved.sourceAgentVersion()).isEqualTo("v1");

        // and the tranche row is still there, findable under its own action
        var t2 = repo.findBySignalIdAndAction(signalId, "ADD_TRANCHE");
        assertThat(t2).isNotNull();
        assertThat(t2.runId()).isEqualTo("run-t2");
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
