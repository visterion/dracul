package de.visterion.dracul.voievod;

import java.util.List;

/** Deterministic consensus math. */
public final class ConsensusScorer {

    private ConsensusScorer() {}

    /** Noisy-OR over independent confidences: 1 - prod(1 - clamp(c)). Empty -> 0. */
    public static double noisyOr(List<Double> confidences) {
        if (confidences.isEmpty()) return 0.0;
        double prod = 1.0;
        for (double c : confidences) {
            double clamped = Math.max(0.0, Math.min(1.0, c));
            prod *= (1.0 - clamped);
        }
        return 1.0 - prod;
    }

    /** Arithmetic mean. Empty -> 0. */
    public static double mean(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.size();
    }
}
