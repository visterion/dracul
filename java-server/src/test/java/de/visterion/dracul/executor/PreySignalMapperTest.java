package de.visterion.dracul.executor;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreySignalMapperTest {

    private final PreySignalMapper mapper = new PreySignalMapper();

    private Prey samplePrey() {
        return new Prey(
                "prey-1", "ACME", "Acme Corp", "SPINOFF",
                0.73, "Forced index selling post-separation.",
                List.of("Dropped from parent's index"), List.of("Thin float"),
                "6m", "strigoi-spin", "2026-07-08T10:00:00Z");
    }

    @Test
    void mapsPreyFieldsOntoSignal() {
        ExecutorSignal s = mapper.map(samplePrey());

        assertThat(s.source()).isEqualTo("strigoi-spin");
        assertThat(s.agentVersion()).isNull();
        assertThat(s.symbol()).isEqualTo("ACME");
        assertThat(s.confidence()).isEqualTo(0.73);
        assertThat(s.mechanism()).isEqualTo("SPINOFF");
        assertThat(s.horizon()).isEqualTo("6m");
        assertThat(s.referencePrice()).isNull();
        assertThat(s.createdAt()).isNull();
    }

    @Test
    void directionIsAlwaysBuy() {
        assertThat(mapper.map(samplePrey()).direction()).isEqualTo("BUY");
    }

    @Test
    void statusIsPending() {
        assertThat(mapper.map(samplePrey()).status()).isEqualTo("PENDING");
    }

    @Test
    void killCriteriaIsEmpty() {
        assertThat(mapper.map(samplePrey()).killCriteria()).isEmpty();
    }

    @Test
    void signalIdIsGeneratedAndNonNull() {
        ExecutorSignal a = mapper.map(samplePrey());
        ExecutorSignal b = mapper.map(samplePrey());
        assertThat(a.signalId()).isNotBlank();
        assertThat(b.signalId()).isNotBlank();
        assertThat(a.signalId()).isNotEqualTo(b.signalId()); // freshly generated each call
    }
}
