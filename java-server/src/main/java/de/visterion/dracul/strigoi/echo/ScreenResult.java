package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;

import java.util.List;

/**
 * Outcome of the deterministic PEAD pre-screen: the surviving candidates plus whether the
 * screen had to drop candidates to stay inside the payload budget or lost the price source
 * partway through.
 *
 * <p>{@code truncated} is not cosmetic — it is the only signal that echo saw MORE qualifying
 * candidates than it showed the agent. Without it a capped list is indistinguishable from a
 * quiet night, which is the exact blindness this branch exists to remove. The controller ORs it
 * into {@code data_source_health.truncated}.
 *
 * <p>{@code priceSourceUnavailable} is the same idea for the price-resolution step: once {@link
 * AgoraMarketData#resolve} raises {@link MarketDataException.Kind#UNAVAILABLE}, every remaining
 * shortlisted symbol needs the very same dead source, so the screen stops resolving prices and
 * reports the outage here instead of quietly returning whatever candidates happened to resolve
 * before the source died (or an empty list with {@code truncated=false}, which used to be
 * indistinguishable from a night with no qualifying earnings beats at all).
 */
public record ScreenResult(List<PeadCandidate> candidates, boolean truncated,
                           boolean priceSourceUnavailable) {
}
