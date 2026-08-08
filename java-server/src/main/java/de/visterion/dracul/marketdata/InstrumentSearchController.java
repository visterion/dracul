package de.visterion.dracul.marketdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Instrument search for Chronicle. Canonical path family for instrument data: /api/instruments/**. */
@RestController
public class InstrumentSearchController {

    private final InstrumentSearchService service;

    public InstrumentSearchController(InstrumentSearchService service) {
        this.service = service;
    }

    @GetMapping("/api/instruments/search")
    public List<InstrumentSearchHit> search(@RequestParam(name = "q", required = false) String q,
                                            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.search(q, limit);
    }
}
