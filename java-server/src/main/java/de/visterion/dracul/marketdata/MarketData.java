package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.util.List;

public record MarketData(
        String companyName,
        BigDecimal currentPrice,
        BigDecimal dayChangePercent,
        String currency,
        List<BigDecimal> priceHistory30d) {

    /** Back-compat: no day-change, currency defaults to USD. */
    public MarketData(String companyName, BigDecimal currentPrice, List<BigDecimal> priceHistory30d) {
        this(companyName, currentPrice, BigDecimal.ZERO, "USD", priceHistory30d);
    }
}
