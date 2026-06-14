package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolCatalogTest {

    private ToolCatalogEntry entry(String name) {
        return new ToolCatalogEntry(name, "desc",
                JsonMapper.builder().build().createObjectNode(), "/api/x/tools/y", 30);
    }

    @Test
    void findsRegisteredToolByName() {
        var catalog = new AgentToolCatalog(List.of(entry("fetch_a"), entry("fetch_b")));
        assertThat(catalog.find("fetch_a")).map(ToolCatalogEntry::toolName).contains("fetch_a");
        assertThat(catalog.contains("fetch_b")).isTrue();
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void rejectsDuplicateToolNames() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AgentToolCatalog(List.of(entry("dup"), entry("dup"))));
    }
}
