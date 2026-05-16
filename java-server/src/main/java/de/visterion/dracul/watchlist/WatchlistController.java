package de.visterion.dracul.watchlist;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WatchlistController {

    private final WatchlistRepository repo;

    public WatchlistController(WatchlistRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/watchlist")
    public List<WatchlistItem> watchlist() {
        return repo.findAllByUser("default");
    }
}
