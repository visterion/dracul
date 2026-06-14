package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void listsCatalogEntriesAsViewsSortedByName() {
        var bTool = new ToolCatalogEntry("b_tool", "desc B", json.createObjectNode(), "/p", 30);
        var aTool = new ToolCatalogEntry("a_tool", "desc A", json.createObjectNode(), "/p", 30);
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(bTool, aTool); }
        };
        var controller = new ToolCatalogController(new AgentToolCatalog(List.of(stub)));

        var out = controller.tools();

        assertThat(out).extracting(ToolCatalogView::toolName).containsExactly("a_tool", "b_tool");
        assertThat(out.get(0).defaultDescription()).isEqualTo("desc A");
    }
}
