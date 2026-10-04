package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** strigoi-tech prompt <-> schema contract (the recurring prompt/schema drift bug class). */
class TechPromptContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String prompt = AgentResources.classpath("prompts/strigoi-tech.md");
    private final JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-tech.json");

    @Test
    void everyItemPropertyIsNamedInThePrompt() {
        List<String> missing = new ArrayList<>();
        for (String section : List.of("prey", "catastrophe_exits")) {
            JsonNode items = schema.path("properties").path(section).path("items");
            for (String property : items.path("properties").propertyNames()) {
                if (!prompt.contains("`" + property + "`")) missing.add(section + "." + property);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void promptNamesTheToolsAndTheCatastropheRuleButNoKillLevel() {
        assertThat(prompt).contains("`fetch_tech_book`").contains("`check_tech_candidate`")
                .contains("`catastrophe_exits`")
                .contains("Price weakness alone is never a catastrophe")
                .doesNotContain("kill_close_below");
    }

    @Test
    void onlyPreyIsRequiredAndKillCriteriaStaysMandatory() {
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        assertThat(required).containsExactly("prey");
        List<String> itemRequired = new ArrayList<>();
        schema.path("properties").path("prey").path("items").path("required")
                .forEach(r -> itemRequired.add(r.asString()));
        assertThat(itemRequired).contains("kill_criteria", "symbol", "confidence");
        assertThat(schema.path("properties").path("prey").path("items").path("properties")
                .path("anomalyType").path("const").asString()).isEqualTo("TECH_CONVICTION");
    }
}
