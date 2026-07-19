package de.visterion.dracul.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Code-bound registry of every available tool, assembled from all providers. */
@Component
public class AgentToolCatalog {

    private final Map<String, ToolCatalogEntry> byName = new LinkedHashMap<>();

    /** Convenience for existing unit tests that only exercise {@link AgentDefaultProvider}
     *  entries; production wiring always goes through the two-arg constructor below. */
    public AgentToolCatalog(List<AgentDefaultProvider> providers) {
        this(providers, List.of());
    }

    @Autowired
    public AgentToolCatalog(List<AgentDefaultProvider> providers,
                            List<ToolCatalogContributor> contributors) {
        for (var provider : providers) {
            for (var e : provider.catalogEntries()) {
                if (byName.putIfAbsent(e.toolName(), e) != null) {
                    throw new IllegalStateException("duplicate tool in catalog: " + e.toolName());
                }
            }
        }
        for (var contributor : contributors) {
            for (var e : contributor.catalogEntries()) {
                if (byName.putIfAbsent(e.toolName(), e) != null) {
                    throw new IllegalStateException("duplicate tool in catalog: " + e.toolName());
                }
            }
        }
    }

    public Optional<ToolCatalogEntry> find(String toolName) {
        return Optional.ofNullable(byName.get(toolName));
    }

    public boolean contains(String toolName) {
        return byName.containsKey(toolName);
    }

    /** Every registered tool entry (for the catalog REST endpoint). */
    public List<ToolCatalogEntry> all() {
        return List.copyOf(byName.values());
    }
}
