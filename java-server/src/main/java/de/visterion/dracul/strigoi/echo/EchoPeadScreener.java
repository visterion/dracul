package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.AgoraMarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic long-only PEAD pre-screen. Keeps an earnings observation only if
 * the actual EPS beat the estimate, the surprise clears the configured threshold,
 * and the symbol's current price clears the configured liquidity floor.
 *
 * <p>The screen is capped at {@code dracul.strigoi.echo.max-candidates} — see the constructor
 * parameter for why, and {@link ScreenResult} for how the cap is reported.
 */
@Component
public class EchoPeadScreener {

    private static final Logger log = LoggerFactory.getLogger(EchoPeadScreener.class);

    /** Deterministic candidate order: strongest EPS surprise first, {@code symbol} as tiebreak so
     *  two candidates with an identical surprise value never swap places between runs. Without
     *  the tiebreak the cap below would silently pick a different set on a tie. */
    private static final Comparator<EarningsObservation> STRONGEST_FIRST =
            Comparator.comparing(EarningsObservation::epsSurprisePercent).reversed()
                    .thenComparing(EarningsObservation::symbol);

    private final AgoraMarketData marketData;
    private final BigDecimal minSurprisePercent;
    private final BigDecimal minPrice;
    private final int maxCandidates;

    /**
     * @param maxCandidates hard ceiling on the candidate list, default 33. This is a
     *        payload-budget bound, not a curation step. Recalibrated 2026-08-04 against the REAL
     *        bridge limit rather than the old "~95 kB" folklore: the claude-bridge skips
     *        truncation only below 12 500 tokens = 50 000 characters, so that is the guaranteed
     *        safe zone. At ~1 365 B per serialized candidate (measured on a production payload,
     *        with the news index cut from 5 to 3 items) 33 candidates measure 45 806 B and leave
     *        ~4 194 B for {@code active_patterns} growth — see {@code EchoPayloadBudgetTest},
     *        which binds its worst case to this very yaml default. Past the limit the bridge
     *        offloads the tool result into a file the agent cannot read and echo silently
     *        returns empty prey, the exact 7-day outage of 2026-07-22.
     *        <p>Capping here is still a net GAIN in coverage: before this change echo asked Agora
     *        for an implicit 100 raw rows — cut by ascending date, i.e. the OLDEST ones — and
     *        turned them into ~28 candidates. It now reads up to 1000 raw rows and shows the
     *        STRONGEST of them, and the cut is reported via {@link ScreenResult#truncated()}
     *        instead of being silent.
     */
    public EchoPeadScreener(
            AgoraMarketData marketData,
            @Value("${dracul.strigoi.echo.min-surprise-percent:5.0}") BigDecimal minSurprisePercent,
            @Value("${dracul.strigoi.echo.min-price:5.0}") BigDecimal minPrice,
            @Value("${dracul.strigoi.echo.max-candidates:40}") int maxCandidates) {
        this.marketData = marketData;
        this.minSurprisePercent = minSurprisePercent;
        this.minPrice = minPrice;
        this.maxCandidates = maxCandidates;
    }

    /**
     * Applies the cheap EPS filters to every observation, ranks the survivors strongest-first,
     * cuts to {@code maxCandidates}, and only THEN resolves prices.
     *
     * <p>The order matters for cost, not just for the payload: {@link AgoraMarketData#resolve}
     * makes two uncached Agora calls per symbol on a {@code synchronized} client, so resolving
     * before the cut would pay up to 1000 serialized lookups to throw most of them away. The
     * price filter therefore runs on at most {@code maxCandidates} symbols — which means the
     * returned list can be SHORTER than the cap even when {@code truncated} is true.
     */
    public ScreenResult screen(List<EarningsObservation> events) {
        List<EarningsObservation> qualifying = new ArrayList<>();
        for (EarningsObservation e : events) {
            if (e.epsActual() == null || e.epsEstimate() == null) continue;
            if (e.epsActual().compareTo(e.epsEstimate()) <= 0) continue;          // positive only
            if (e.epsSurprisePercent() == null
                    || e.epsSurprisePercent().compareTo(minSurprisePercent) < 0) continue;
            qualifying.add(e);
        }

        boolean truncated = qualifying.size() > maxCandidates;
        qualifying.sort(STRONGEST_FIRST);
        List<EarningsObservation> shortlist =
                truncated ? qualifying.subList(0, maxCandidates) : qualifying;

        List<PeadCandidate> out = new ArrayList<>();
        boolean priceSourceUnavailable = false;
        for (EarningsObservation e : shortlist) {
            if (priceSourceUnavailable) {
                // The sole strict price source is down; every remaining shortlisted symbol
                // needs it too — same skip-the-rest-of-the-batch discipline as
                // IndexEventEnricher.enrich for the same MarketDataException.Kind#UNAVAILABLE.
                break;
            }
            BigDecimal price;
            try {
                price = marketData.resolve(e.symbol()).currentPrice();
            } catch (MarketDataException ex) {
                if (ex.kind() == MarketDataException.Kind.UNAVAILABLE) {
                    priceSourceUnavailable = true;
                    log.warn("agora source unavailable: tool=get_quote subject={} — {}",
                            e.symbol(), ex.getMessage());
                } else {
                    log.debug("echo pead screen: price lookup failed for {}: {}", e.symbol(), ex.getMessage());
                }
                continue;                                                          // liquidity unverifiable
            }
            if (price.compareTo(minPrice) < 0) continue;
            out.add(new PeadCandidate(
                    e.symbol(), e.companyName(), e.reportDate(),
                    e.epsActual(), e.epsEstimate(), e.epsSurprisePercent(),
                    e.revenueActual(), e.revenueEstimate(), price));
        }
        return new ScreenResult(out, truncated, priceSourceUnavailable);
    }
}
