package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.MarketDataPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic long-only PEAD pre-screen. Keeps an earnings observation only if
 * the actual EPS beat the estimate, the surprise clears the configured threshold,
 * and the symbol's current price clears the configured liquidity floor.
 */
@Component
public class EchoPeadScreener {

    private final MarketDataPort marketData;
    private final BigDecimal minSurprisePercent;
    private final BigDecimal minPrice;

    public EchoPeadScreener(
            MarketDataPort marketData,
            @Value("${dracul.strigoi.echo.min-surprise-percent:5.0}") BigDecimal minSurprisePercent,
            @Value("${dracul.strigoi.echo.min-price:5.0}") BigDecimal minPrice) {
        this.marketData = marketData;
        this.minSurprisePercent = minSurprisePercent;
        this.minPrice = minPrice;
    }

    public List<PeadCandidate> screen(List<EarningsObservation> events) {
        List<PeadCandidate> out = new ArrayList<>();
        for (EarningsObservation e : events) {
            if (e.epsActual() == null || e.epsEstimate() == null) continue;
            if (e.epsActual().compareTo(e.epsEstimate()) <= 0) continue;          // positive only
            if (e.epsSurprisePercent() == null
                    || e.epsSurprisePercent().compareTo(minSurprisePercent) < 0) continue;
            BigDecimal price;
            try {
                price = marketData.resolve(e.symbol()).currentPrice();
            } catch (MarketDataException ex) {
                continue;                                                          // liquidity unverifiable
            }
            if (price.compareTo(minPrice) < 0) continue;
            out.add(new PeadCandidate(
                    e.symbol(), e.companyName(), e.reportDate(),
                    e.epsActual(), e.epsEstimate(), e.epsSurprisePercent(),
                    e.revenueActual(), e.revenueEstimate(), price));
        }
        return out;
    }
}
