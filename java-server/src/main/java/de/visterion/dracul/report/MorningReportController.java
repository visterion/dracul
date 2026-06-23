package de.visterion.dracul.report;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/morning-report")
public class MorningReportController {

    private final MorningReportService service;

    public MorningReportController(MorningReportService service) {
        this.service = service;
    }

    @GetMapping
    public MorningReport get() {
        return service.build(CurrentUserHolder.get());
    }
}
