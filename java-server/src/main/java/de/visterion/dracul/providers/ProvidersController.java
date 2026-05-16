package de.visterion.dracul.providers;

import de.visterion.dracul.vistierie.LlmProvider;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProvidersController {

    private final VistierieClient vistierieClient;

    public ProvidersController(VistierieClient vistierieClient) {
        this.vistierieClient = vistierieClient;
    }

    @GetMapping("/api/providers")
    public List<LlmProvider> providers() {
        return vistierieClient.getProviders();
    }
}
