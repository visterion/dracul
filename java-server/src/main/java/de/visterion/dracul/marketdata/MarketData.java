package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.util.List;

public record MarketData(
        String companyName,
        BigDecimal currentPrice,
        List<BigDecimal> priceHistory30d
) {}
