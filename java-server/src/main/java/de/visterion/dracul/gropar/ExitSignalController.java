package de.visterion.dracul.gropar;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/exit-signals")
public class ExitSignalController {

    private final ExitSignalRepository repo;

    public ExitSignalController(ExitSignalRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ExitSignal> list() {
        return repo.findLatestByUser(CurrentUserHolder.get(), 100);
    }
}
