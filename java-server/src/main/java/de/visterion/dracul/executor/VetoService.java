package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Code-enforced pre-trade vetos. Slice 1: SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS.
 * Pure and deterministic — no I/O, no clock. The LLM's judgment never overrides these.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class VetoService {

    /** Result of evaluating all slice-1 vetos against one signal. */
    public record Outcome(boolean passed, RejectReason firstFailure, List<VetoResult> results) {}

    public Outcome evaluate(ExecutorSignal signal, int openPositions,
                            double minConfidence, int maxPositions) {
        List<VetoResult> results = new ArrayList<>();
        RejectReason firstFailure = null;

        boolean schemaOk = signal != null
                && signal.symbol() != null && !signal.symbol().isBlank()
                && signal.direction() != null && !signal.direction().isBlank()
                && signal.confidence() != null;
        results.add(new VetoResult("SCHEMA_INVALID", schemaOk));
        if (!schemaOk) firstFailure = RejectReason.SCHEMA_INVALID;

        // Confidence is only meaningful once schema passed (confidence non-null).
        boolean confidenceOk = schemaOk && signal.confidence() >= minConfidence;
        results.add(new VetoResult("LOW_CONFIDENCE", confidenceOk));
        if (!confidenceOk && firstFailure == null) firstFailure = RejectReason.LOW_CONFIDENCE;

        boolean capacityOk = openPositions < maxPositions;
        results.add(new VetoResult("MAX_POSITIONS", capacityOk));
        if (!capacityOk && firstFailure == null) firstFailure = RejectReason.MAX_POSITIONS;

        boolean passed = firstFailure == null;
        return new Outcome(passed, firstFailure, results);
    }
}
