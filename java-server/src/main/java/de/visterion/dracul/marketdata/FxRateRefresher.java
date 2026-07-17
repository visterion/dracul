package de.visterion.dracul.marketdata;

import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Warms the {@link FxService} rate cache in the background so the watchlist/portfolio read never
 * blocks on a live FX fetch — mirrors {@link de.visterion.dracul.watchlist.WatchlistPriceRefresher}.
 * Runs at startup and every 30 minutes. Never throws out of the scheduled method.
 */
@Component
@ConditionalOnProperty(value = "dracul.marketdata.fx-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class FxRateRefresher {

    private static final Logger log = LoggerFactory.getLogger(FxRateRefresher.class);

    private final FxService fx;
    private final AppSettingsRepository settings;
    private final WatchlistRepository watchlistRepo;
    private final VerdictRepository verdictRepo;
    private final HeldPositionService heldPositions;
    private final String connection;

    public FxRateRefresher(FxService fx, AppSettingsRepository settings,
                           WatchlistRepository watchlistRepo, VerdictRepository verdictRepo,
                           HeldPositionService heldPositions,
                           @Value("${dracul.position.connection:depot-1}") String connection) {
        this.fx = fx;
        this.settings = settings;
        this.watchlistRepo = watchlistRepo;
        this.verdictRepo = verdictRepo;
        this.heldPositions = heldPositions;
        this.connection = connection;
    }

    @Scheduled(
            initialDelayString = "${dracul.marketdata.fx-refresh.initial-delay-ms:0}",
            fixedDelayString = "${dracul.marketdata.fx-refresh.fixed-delay-ms:1800000}")
    public void refresh() {
        try {
            String display = settings.getDisplayCurrency();
            Set<String> currencies = new HashSet<>();
            currencies.addAll(watchlistRepo.distinctCurrencies());
            currencies.addAll(verdictRepo.distinctCurrencies());
            // T2.2 (round 3, F2): also warm the depot positions' native currencies toward the
            // display currency so portfolio weights never depend on coincidental overlap.
            // openPositions degrades to an empty list on a depot outage — nothing to warm then.
            for (de.visterion.dracul.position.HeldPosition p : heldPositions.openPositions(connection)) {
                if (p.currency() != null) currencies.add(p.currency());
            }
            for (String c : currencies) {
                if (c != null && !c.equalsIgnoreCase(display)) fx.warm(c, display);
            }
        } catch (RuntimeException e) {
            log.warn("FX rate refresh failed: {}", e.getMessage());
        }
    }
}
