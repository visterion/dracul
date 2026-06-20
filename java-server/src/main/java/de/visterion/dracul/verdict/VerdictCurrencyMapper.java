package de.visterion.dracul.verdict;

import de.visterion.dracul.marketdata.FxService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class VerdictCurrencyMapper {

    private final FxService fx;

    public VerdictCurrencyMapper(FxService fx) {
        this.fx = fx;
    }

    /** Returns a copy of {@code v} with currentPrice converted to {@code display} + native preserved. */
    public VerdictDetail toDisplay(VerdictDetail v, String display) {
        var nativeCur = v.currency() == null ? "USD" : v.currency();
        var converted = fx.convert(BigDecimal.valueOf(v.currentPrice()), nativeCur, display);
        double convertedPrice = converted == null ? v.currentPrice() : converted.doubleValue();
        return v.withConverted(convertedPrice, display, v.currentPrice(), nativeCur);
    }
}
