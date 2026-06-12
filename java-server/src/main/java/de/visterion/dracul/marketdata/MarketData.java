package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.util.List;

public record MarketData(
        String companyName,
        BigDecimal currentPrice,
        BigDecimal dayChangePercent,
        List<BigDecimal> priceHistory30d) {

    /** Convenience constructor for callers without a day-change figure (defaults to 0). */
    public MarketData(String companyName, BigDecimal currentPrice, List<BigDecimal> priceHistory30d) {
        this(companyName, currentPrice, BigDecimal.ZERO, priceHistory30d);
    }
}
