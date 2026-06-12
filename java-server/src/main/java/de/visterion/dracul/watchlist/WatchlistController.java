package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.verdict.VerdictRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import de.visterion.dracul.marketdata.Quote;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
public class WatchlistController {

    private final WatchlistRepository repo;
    private final MarketDataPort marketData;
    private final VerdictRepository verdictRepo;

    public WatchlistController(WatchlistRepository repo,
                                MarketDataPort marketData,
                                VerdictRepository verdictRepo) {
        this.repo = repo;
        this.marketData = marketData;
        this.verdictRepo = verdictRepo;
    }

    @GetMapping("/api/watchlist")
    public List<WatchlistItem> watchlist() {
        List<WatchlistItem> items = repo.findAll();
        if (items.isEmpty()) return items;
        Map<String, Quote> live;
        try {
            live = marketData.quotes(items.stream().map(WatchlistItem::ticker).distinct().toList());
        } catch (RuntimeException e) {
            return items; // provider down → serve stored values unchanged
        }
        return items.stream().map(it -> {
            Quote q = live.get(it.ticker());
            if (q == null) return it; // no fresh quote → keep stored
            return new WatchlistItem(
                    it.id(), it.ticker(), it.companyName(),
                    q.price().doubleValue(), q.dayChangePercent().doubleValue(),
                    it.status(), it.addedAt(), it.tag(), it.verdictId(), it.alerts(),
                    it.priceHistory30d(), it.entryPrice(), it.shareCount(), it.owner());
        }).toList();
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
            return ResponseEntity.ok(merged);
        }

        MarketData md = marketData.resolve(req.symbol());
        List<Double> hist = md.priceHistory30d().stream().map(BigDecimal::doubleValue).toList();
        WatchlistItem created = repo.insert(
                user, req.symbol(), md.companyName(),
                md.currentPrice().doubleValue(), hist,
                req.tag(), req.sourceVerdictId());
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/api/watchlist/{id}")
    public WatchlistItem patch(@PathVariable String id,
                                @Valid @RequestBody PatchWatchlistRequest req) {
        requireOwner(id);
        if (!repo.updateTag(id, req.tag())) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        return repo.findById(id).orElseThrow();
    }

    @PatchMapping("/api/watchlist/{id}/position")
    public WatchlistItem patchPosition(@PathVariable String id,
                                       @Valid @RequestBody PatchPositionRequest req) {
        requireOwner(id);
        if (!repo.updatePosition(id, req.entryPrice(), req.shareCount())) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        return repo.findById(id).orElseThrow();
    }

    @DeleteMapping("/api/watchlist/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        requireOwner(id);
        if (!repo.deleteById(id)) {
            throw new NoSuchElementException("watchlist item " + id);
        }
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
