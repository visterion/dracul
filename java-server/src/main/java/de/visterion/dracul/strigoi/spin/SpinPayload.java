package de.visterion.dracul.strigoi.spin;

import java.util.List;

/**
 * The spin hunter's RESPOND payload plus whether the row cap cut it.
 *
 * <p>The payload is read back from the persisted candidate table, so it has two ways of being
 * incomplete that the ingest search's health knows nothing about: the requested window may exclude
 * tracked rows (intended), and the response row cap may cut the window's tail (a loss). Only the
 * second is a degradation, and it now rides back here instead of leaving the fetch looking clean —
 * the same contract the merger candidate cap follows.
 *
 * @param candidates the enriched candidates actually returned
 * @param truncated  true when the response cap cut the in-window result set
 */
public record SpinPayload(List<EnrichedSpinCandidate> candidates, boolean truncated) {}
