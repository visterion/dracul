package de.visterion.dracul.watchlist;

import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.verdict.VerdictRepository;
import jakarta.validation.Valid;
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

    public WatchlistController(WatchlistRepository repo,
                                MarketDataPort marketData,
                                VerdictRepository verdictRepo) {
        this.repo = repo;
        this.marketData = marketData;
        this.verdictRepo = verdictRepo;
    }

    @GetMapping("/api/watchlist")
    public List<WatchlistItem> watchlist() {
        return repo.findAllByUser("default");
    }

    @PostMapping("/api/watchlist")
    public ResponseEntity<WatchlistItem> create(@Valid @RequestBody CreateWatchlistRequest req) {
        if (req.sourceVerdictId() != null
                && verdictRepo.findDetailById(req.sourceVerdictId()).isEmpty()) {
            throw new NoSuchElementException("verdict " + req.sourceVerdictId());
        }

        Optional<WatchlistItem> existing = repo.findByUserAndTicker("default", req.symbol());
        if (existing.isPresent()) {
            WatchlistItem merged = repo.mergeVerdictIdIfNull(
                    existing.get().id(), req.sourceVerdictId());
            return ResponseEntity.ok(merged);
        }

        MarketData md = marketData.resolve(req.symbol());
        List<Double> hist = md.priceHistory30d().stream().map(BigDecimal::doubleValue).toList();
        WatchlistItem created = repo.insert(
                "default", req.symbol(), md.companyName(),
                md.currentPrice().doubleValue(), hist,
                req.tag(), req.sourceVerdictId());
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/api/watchlist/{id}")
    public WatchlistItem patch(@PathVariable String id,
                                @Valid @RequestBody PatchWatchlistRequest req) {
        if (!repo.updateTag(id, req.tag())) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        return repo.findById(id).orElseThrow();
    }

    @DeleteMapping("/api/watchlist/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.deleteById(id)) {
            throw new NoSuchElementException("watchlist item " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
