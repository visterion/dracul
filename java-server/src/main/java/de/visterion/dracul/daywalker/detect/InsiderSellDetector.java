package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.agora.Form4Filing;
import de.visterion.dracul.watchlist.WatchlistItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fires INSIDER_SELL when any Form-4 sale ("S") exists for the item's ticker. */
public class InsiderSellDetector {

    public Optional<TriggerEvent> detect(WatchlistItem item, List<Form4Filing> allFilings) {
        return allFilings.stream()
                .filter(f -> item.ticker().equalsIgnoreCase(f.ticker()))
                .filter(f -> "S".equalsIgnoreCase(f.transactionCode()))
                .findFirst()
                .map(f -> new TriggerEvent(item.ticker(), item.companyName(),
                        TriggerType.INSIDER_SELL, BigDecimal.valueOf(item.currentPrice()),
                        Map.of("filer_name", f.filerName(),
                                "shares", f.sharesAcquired(),
                                "dollar_value", f.dollarValue(),
                                "transaction_date", f.transactionDate().toString())));
    }
}
