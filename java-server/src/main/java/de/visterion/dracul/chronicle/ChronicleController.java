package de.visterion.dracul.chronicle;

import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ChronicleController {

    private final PreyRepository preyRepo;
    private final VerdictRepository verdictRepo;
    private final PatternRepository patternRepo;

    public ChronicleController(PreyRepository preyRepo,
                                VerdictRepository verdictRepo,
                                PatternRepository patternRepo) {
        this.preyRepo = preyRepo;
        this.verdictRepo = verdictRepo;
        this.patternRepo = patternRepo;
    }

    @GetMapping("/api/chronicle")
    public ChronicleData chronicle(
            @RequestParam(name = "includeDismissed", defaultValue = "false") boolean includeDismissed) {
        return new ChronicleData(
                preyRepo.findAllByUser("default"),
                verdictRepo.findAllByUser("default", includeDismissed),
                List.of(),
                patternRepo.findPendingByUser("default")
        );
    }
}
