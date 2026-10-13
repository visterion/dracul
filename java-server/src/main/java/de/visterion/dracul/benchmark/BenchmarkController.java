package de.visterion.dracul.benchmark;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Human read side (Cloudflare Access, NOT in CloudflareAccessFilter.EXCLUDED). */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final BenchmarkSummaryService service;

    public BenchmarkController(BenchmarkSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public BenchmarkSummary summary() {
        return service.summary(CurrentUserHolder.get());
    }
}
