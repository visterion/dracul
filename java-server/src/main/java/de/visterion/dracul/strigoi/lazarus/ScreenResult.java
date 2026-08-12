package de.visterion.dracul.strigoi.lazarus;

import java.util.List;

/**
 * The screener's output PLUS the implausible-range count it filtered out silently.
 *
 * <p>A candidate whose current price sits BELOW its own 52-week low is not a real "at the low"
 * signal — it means the price and the low are quoted in different units (measured on prod Agora,
 * 2026-08-09: BRK.B returns {@code price 520.96} against {@code 52WeekLow 693021}, the low in
 * A-share units against a price in B-share units) or one of the two numbers is simply wrong.
 * Either way the row is discarded, and {@code implausibleRange} counts how many were.
 *
 * <p>It stays a separate counter, never folded into a health/{@code partial} flag, for the same
 * reason {@code notEligible} and {@code probeFailed} are split apart in
 * {@link LazarusUniverseService}: this is a DATA error about the instrument, not a source outage
 * — no retry, no failover and no bigger budget will ever change it.
 *
 * @param candidates        the candidates that passed every gate
 * @param implausibleRange  how many rows were dropped for a current price below their own
 *                          52-week low
 */
public record ScreenResult(List<LazarusCandidate> candidates, int implausibleRange) {
}
