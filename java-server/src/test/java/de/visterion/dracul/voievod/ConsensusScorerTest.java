package de.visterion.dracul.voievod;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConsensusScorerTest {

    @Test
    void noisyOrCombinesIndependentConfidences() {
        assertThat(ConsensusScorer.noisyOr(List.of(0.7, 0.6))).isEqualTo(0.88, within(1e-9));
        assertThat(ConsensusScorer.noisyOr(List.of(0.5, 0.5, 0.5))).isEqualTo(0.875, within(1e-9));
    }

    @Test
    void noisyOrIsMonotonic() {
        double two = ConsensusScorer.noisyOr(List.of(0.7, 0.6));
        double three = ConsensusScorer.noisyOr(List.of(0.7, 0.6, 0.4));
        assertThat(three).isGreaterThanOrEqualTo(two);
    }

    @Test
    void edgeCases() {
        assertThat(ConsensusScorer.noisyOr(List.of())).isEqualTo(0.0);
        assertThat(ConsensusScorer.noisyOr(List.of(1.5, -0.2))).isEqualTo(1.0, within(1e-9));
        assertThat(ConsensusScorer.mean(List.of(0.7, 0.5))).isEqualTo(0.6, within(1e-9));
        assertThat(ConsensusScorer.mean(List.of())).isEqualTo(0.0);
    }
}
