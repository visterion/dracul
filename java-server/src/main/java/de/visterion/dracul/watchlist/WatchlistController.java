package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.AgoraMarketData;
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
    private final AgoraMarketData marketData;
    private final VerdictRepository verdictRepo;
    private final ApplicationEventPublisher events;
    private final AppSettingsRepository settings;
    private final WatchlistCurrencyMapper mapper;

    public WatchlistController(WatchlistRepository repo,
                                AgoraMarketData marketData,
                                VerdictRepository verdictRepo,
                                ApplicationEventPublisher events,
                                AppSettingsRepository settings,
                                WatchlistCurrencyMapper mapper) {
        this.repo = repo;
        this.marketData = marketData;
        this.verdictRepo = verdictRepo;
        this.events = events;
        this.settings = settings;
        this.mapper = mapper;
    }

    @GetMapping("/api/watchlist")
    public List<WatchlistItem> watchlist() {
        // Prices are kept fresh by WatchlistPriceRefresher; serve stored values (no per-load call).
        String display = settings.getDisplayCurrency();
        return repo.findAll().stream()
                .map(i -> mapper.toDisplay(i, display))
                .toList();
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
            return ResponseEntity.ok(mapper.toDisplay(merged, settings.getDisplayCurrency()));
        }

        MarketData md = marketData.resolve(req.symbol());
        List<Double> hist = md.priceHistory30d().stream().map(BigDecimal::doubleValue).toList();
        // NOTE(7b): Agora get_quote returns no company name → md.companyName() is the ticker (display fallback).
        String source = req.sourceVerdictId() != null ? "verdict" : "manual";
        WatchlistItem created = repo.insert(
                user, req.symbol(), md.companyName(),
                md.currentPrice().doubleValue(), hist,
                req.tag(), source, req.sourceVerdictId(), md.currency());
        events.publishEvent(new WatchlistChangedEvent());
        return ResponseEntity.status(201).body(mapper.toDisplay(created, settings.getDisplayCurrency()));
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
        if (req.entryDate() != null) {
            repo.updateEntryDate(id, req.entryDate());
        }
        events.publishEvent(new WatchlistChangedEvent());
        return mapper.toDisplay(repo.findById(id).orElseThrow(), settings.getDisplayCurrency());
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
