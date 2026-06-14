package de.visterion.dracul.agent;

import java.util.List;

/** Each agent area contributes its code-default definition + tool catalog entries.
 *  The registrar/bootstrap iterate over all providers; this keeps domain ownership
 *  per-package while registration stays generic. */
public interface AgentDefaultProvider {
    AgentDefinition defaultDefinition();
    default List<ToolCatalogEntry> catalogEntries() {
        return List.of();
    }
}
