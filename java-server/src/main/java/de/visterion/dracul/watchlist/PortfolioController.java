package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.settings.AppSettingsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Personal portfolio view: the current user's held positions only. Distinct from the
 * collaborative {@code /api/watchlist} (which serves all users). A position is a watchlist
 * item with both an entry price and a share count.
 */
@RestController
public class PortfolioController {

    private final WatchlistRepository repo;
    private final AppSettingsRepository settings;
    private final WatchlistCurrencyMapper mapper;

    public PortfolioController(WatchlistRepository repo, AppSettingsRepository settings,
                               WatchlistCurrencyMapper mapper) {
        this.repo = repo;
        this.settings = settings;
        this.mapper = mapper;
    }

    @GetMapping("/api/portfolio")
    public List<WatchlistItem> portfolio() {
        String display = settings.getDisplayCurrency();
        return repo.findAllByUser(CurrentUserHolder.get()).stream()
                .filter(i -> i.entryPrice() != null && i.shareCount() != null)
                .map(i -> mapper.toDisplay(i, display))
                .toList();
    }
}
