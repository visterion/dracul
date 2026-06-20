package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
public class WatchlistController {

    private final WatchlistRepository repo;
    private final MarketDataPort marketData;
    private final VerdictRepository verdictRepo;
    private final ApplicationEventPublisher events;
    private final AppSettingsRepository settings;

    public WatchlistController(WatchlistRepository repo,
                                MarketDataPort marketData,
                                VerdictRepository verdictRepo,
                                ApplicationEventPublisher events,
                                AppSettingsRepository settings) {
        this.repo = repo;
        this.marketData = marketData;
        this.verdictRepo = verdictRepo;
        this.events = events;
        this.settings = settings;
    }

    @GetMapping("/api/watchlist")
    public List<WatchlistItem> watchlist() {
        // Prices are kept fresh by WatchlistPriceRefresher; serve stored values (no per-load call).
        return repo.findAll();
    }

    @PostMapping("/api/watchlist")
    public ResponseEntity<WatchlistItem> create(@Valid @RequestBody CreateWatchlistRequest req) {
        if (req.sourceVerdictId() != null
                && verdictRepo.findDetailById(req.sourceVerdictId()).isEmpty()) {
            throw new NoSuchElementException("verdict " + req.sourceVerdictId());
        }

        String user = CurrentUserHolder.get();
        Optional<WatchlistItem> existing = repo.findByUserAndTicker(user, req.symbol());
        if (existing.isPresent()) {
            WatchlistItem merged = repo.mergeVerdictIdIfNull(
                    existing.get().id(), req.sourceVerdictId());
            events.publishEvent(new WatchlistChangedEvent());
            return ResponseEntity.ok(merged);
        }

        MarketData md = marketData.resolve(req.symbol());
        List<Double> hist = md.priceHistory30d().stream().map(BigDecimal::doubleValue).toList();
        WatchlistItem created = repo.insert(
                user, req.symbol(), md.companyName(),
                md.currentPrice().doubleValue(), hist,
                req.tag(), req.sourceVerdictId(), md.currency());
        events.publishEvent(new WatchlistChangedEvent());
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/api/watchlist/{id}")
    public WatchlistItem patch(@PathVariable String id,
                                @Valid @RequestBody PatchWatchlistRequest req) {
        requireOwner(id);
        if (!repo.updateTag(id, req.tag())) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        events.publishEvent(new WatchlistChangedEvent());
        return repo.findById(id).orElseThrow();
    }

    @PatchMapping("/api/watchlist/{id}/position")
    public WatchlistItem patchPosition(@PathVariable String id,
                                       @Valid @RequestBody PatchPositionRequest req) {
        requireOwner(id);
        if (!repo.updatePosition(id, req.entryPrice(), req.shareCount(),
                settings.getDisplayCurrency())) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        events.publishEvent(new WatchlistChangedEvent());
        return repo.findById(id).orElseThrow();
    }

    @DeleteMapping("/api/watchlist/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        requireOwner(id);
        if (!repo.deleteById(id)) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        events.publishEvent(new WatchlistChangedEvent());
        return ResponseEntity.noContent().build();
    }

    private void requireOwner(String id) {
        WatchlistItem item = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("watchlist item " + id));
        if (!item.owner().equals(CurrentUserHolder.get())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "not your watchlist item");
        }
    }
}
