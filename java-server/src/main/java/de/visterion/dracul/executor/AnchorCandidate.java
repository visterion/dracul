package de.visterion.dracul.executor;

/** A signal missing counterfactual anchors that qualifies for nightly reconstruction. */
public record AnchorCandidate(String signalId, String symbol, java.time.Instant emittedAt) {
}
