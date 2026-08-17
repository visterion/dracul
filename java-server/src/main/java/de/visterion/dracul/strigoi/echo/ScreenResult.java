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
 * <p>{@code priceSourceUnavailable} is the same idea for the price-resolution step, and it now has
 * TWO triggers instead of one: immediately when {@link AgoraMarketData#resolve} raises {@link
 * MarketDataException.Kind#UNAVAILABLE} ({@code AgoraUnavailableException.Scope#SOURCE} — Agora
 * produced no answer at all), or after three consecutive per-request failures with no success in
 * between ({@code Scope#REQUEST} — Agora answered with an error envelope for every symbol tried, the
 * actual shape of the 2026-08-06 incident). Either way every remaining shortlisted symbol is treated
 * as needing the same dead source, so the screen stops resolving prices and reports the outage here
 * instead of quietly returning whatever candidates happened to resolve before the source died (or an
 * empty list with {@code truncated=false}, which used to be indistinguishable from a night with no
 * qualifying earnings beats at all). A single per-request failure alone does not trip this — it only
 * costs that one candidate.
 */
public record ScreenResult(List<PeadCandidate> candidates, boolean truncated,
                           boolean priceSourceUnavailable) {
}
