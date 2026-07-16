package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        // The prey under test are discovered by "strigoi-spin". The emitter's version gate
        // resolves the agent version from the agent_definition row, which is only seeded when
        // the strigoi-spin provider bean exists. Enabling it here makes this test's own context
        // bootstrap that row, so versionFor() returns a known hash (not the "unknown" sentinel
        // that makes the emitter skip the fresh prey) regardless of test ordering.
        "dracul.strigoi.spin.enabled=true"
})
class PreySignalEmitterIT {

    @Autowired PreySignalEmitter emitter;
    @Autowired ExecutorSignalRepository signalRepo;
    @Autowired ExecutorPositionRepository positionRepo;

    private Prey prey(String symbol) {
        return new Prey(
                "prey-" + symbol, symbol, symbol + " Corp", "SPINOFF",
                0.7, "thesis", List.of("signal"), List.of("risk"),
                List.of("kill"),
                "6m", "strigoi-spin", "2026-07-08T10:00:00Z");
    }

    private ExecutorPosition openPosition(String symbol) {
        return new ExecutorPosition(
                null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("95"), 1, new BigDecimal("5"),
                List.of(), "sig-" + symbol, "strigoi-spin", null,
                null, "OPEN", null, null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);
    }

    @Test
    void emitsFreshSkipsOpenAndPending() {
        String fresh = "FRESHIT" + System.nanoTime();
        String open = "OPENIT" + System.nanoTime();
        String pending = "PENDIT" + System.nanoTime();

        // seed: an open position and a pending signal
        positionRepo.insert(openPosition(open));
        signalRepo.insert(new ExecutorSignal(
                java.util.UUID.randomUUID().toString(), "strigoi-spin", null, pending,
                "BUY", 0.6, "SPINOFF", List.of(), "6m", null, "PENDING", null));

        emitter.emit(List.of(prey(fresh), prey(open), prey(pending)));

        var pendingNow = signalRepo.findPending(Integer.MAX_VALUE);

        // fresh symbol -> a new signal was inserted
        assertThat(pendingNow).anySatisfy(s -> {
            assertThat(s.symbol()).isEqualTo(fresh);
            assertThat(s.direction()).isEqualTo("BUY");
            assertThat(s.mechanism()).isEqualTo("SPINOFF");
            assertThat(s.source()).isEqualTo("strigoi-spin");
            assertThat(s.status()).isEqualTo("PENDING");
        });

        // open-position symbol -> skipped (no signal emitted for it)
        assertThat(pendingNow).noneSatisfy(s -> assertThat(s.symbol()).isEqualTo(open));

        // already-pending symbol -> still exactly one pending signal (no duplicate)
        assertThat(pendingNow).filteredOn(s -> s.symbol().equals(pending)).hasSize(1);
    }

    @Test
    void emit_persistsPreyThesisOnSignal() {
        String symbol = "HELE" + System.nanoTime();
        Prey p = new Prey(
                "prey-" + symbol, symbol, symbol + " Corp", "PEAD",
                0.7, "big beat", List.of("s"), List.of("r"),
                List.of("k"), "1M", "strigoi-spin", "2026-07-08T10:00:00Z");

        emitter.emit(List.of(p));

        ExecutorSignal saved = signalRepo.findPending(Integer.MAX_VALUE).stream()
                .filter(s -> symbol.equals(s.symbol())).findFirst().orElseThrow();
        assertThat(saved.thesis()).isNotNull();                       // fails if the emitter drops s.thesis()
        assertThat(saved.thesis().get("summary").asString()).isEqualTo("big beat");
    }
}
