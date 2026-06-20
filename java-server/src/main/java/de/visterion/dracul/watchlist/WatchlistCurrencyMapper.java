package de.visterion.dracul.watchlist;

import de.visterion.dracul.marketdata.FxService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class WatchlistCurrencyMapper {

    private final FxService fx;

    public WatchlistCurrencyMapper(FxService fx) {
        this.fx = fx;
    }

    /** Returns a copy of {@code i} with prices converted to {@code display} and currency=display. */
    public WatchlistItem toDisplay(WatchlistItem i, String display) {
        var nativeCur = i.currency() == null ? "USD" : i.currency();
        var entryCur  = i.entryCurrency() == null ? display : i.entryCurrency();
        var current = fx.convert(BigDecimal.valueOf(i.currentPrice()), nativeCur, display);
        var entry   = i.entryPrice() == null ? null
                : fx.convert(BigDecimal.valueOf(i.entryPrice()), entryCur, display);
        return i.withConverted(current, entry, display, nativeCur);
    }
}
