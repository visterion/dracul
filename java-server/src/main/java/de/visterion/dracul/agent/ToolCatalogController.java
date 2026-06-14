package de.visterion.dracul.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Lists the available tool catalog so the agent-config UI can offer a tool checklist. */
@RestController
@RequestMapping("/api/settings/agents")
public class ToolCatalogController {

    private final AgentToolCatalog catalog;

    public ToolCatalogController(AgentToolCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/tools")
    public List<ToolCatalogView> tools() {
        return catalog.all().stream()
                .map(e -> new ToolCatalogView(e.toolName(), e.defaultDescription()))
                .sorted(Comparator.comparing(ToolCatalogView::toolName))
                .toList();
    }
}
