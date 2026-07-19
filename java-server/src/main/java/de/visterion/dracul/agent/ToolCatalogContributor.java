package de.visterion.dracul.agent;

import java.util.List;

/** Contributes tool-catalog entries independent of any agent's default definition — for tools
 *  (like the shared HiveMem "search" mcp tool) that don't belong to any single *Defaults bean
 *  and must exist in the catalog unconditionally, before any binding references them. */
public interface ToolCatalogContributor {
    List<ToolCatalogEntry> catalogEntries();
}
