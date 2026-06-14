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

    private AgentDefaultProvider providerWith(ToolCatalogEntry... entries) {
        return new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(entries); }
        };
    }

    @Test
    void findsRegisteredToolByName() {
        var catalog = new AgentToolCatalog(List.of(
                providerWith(entry("fetch_a")),
                providerWith(entry("fetch_b"))
        ));
        assertThat(catalog.find("fetch_a")).map(ToolCatalogEntry::toolName).contains("fetch_a");
        assertThat(catalog.contains("fetch_b")).isTrue();
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void rejectsDuplicateToolNames() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AgentToolCatalog(List.of(
                        providerWith(entry("dup")),
                        providerWith(entry("dup"))
                )));
    }

    @Test
    void allReturnsEveryEntryAndDefaultsCacheableTrue() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var legacy = new ToolCatalogEntry("t_legacy", "d", json.createObjectNode(), "/p", 30);
        var explicit = new ToolCatalogEntry("t_explicit", "d", json.createObjectNode(), "/p", 30, false, 60);
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public java.util.List<ToolCatalogEntry> catalogEntries() {
                return java.util.List.of(legacy, explicit);
            }
        };
        var catalog = new AgentToolCatalog(java.util.List.of(stub));
        assertThat(catalog.all()).extracting(ToolCatalogEntry::toolName)
                .containsExactlyInAnyOrder("t_legacy", "t_explicit");
        assertThat(catalog.find("t_legacy").orElseThrow().cacheable()).isTrue();
        assertThat(catalog.find("t_legacy").orElseThrow().cacheTtlSeconds()).isNull();
        assertThat(catalog.find("t_explicit").orElseThrow().cacheable()).isFalse();
        assertThat(catalog.find("t_explicit").orElseThrow().cacheTtlSeconds()).isEqualTo(60);
    }
}
