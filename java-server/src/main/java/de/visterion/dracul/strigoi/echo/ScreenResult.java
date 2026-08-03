package de.visterion.dracul.strigoi.echo;

import java.util.List;

/**
 * Outcome of the deterministic PEAD pre-screen: the surviving candidates plus whether the
 * screen had to drop candidates to stay inside the payload budget.
 *
 * <p>{@code truncated} is not cosmetic — it is the only signal that echo saw MORE qualifying
 * candidates than it showed the agent. Without it a capped list is indistinguishable from a
 * quiet night, which is the exact blindness this branch exists to remove. The controller ORs it
 * into {@code data_source_health.truncated}.
 */
public record ScreenResult(List<PeadCandidate> candidates, boolean truncated) {
}
