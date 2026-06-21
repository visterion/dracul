package de.visterion.dracul.gropar;

import de.visterion.dracul.agent.AgentResources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards gropar's exit-signal output contract: the thesis_status enum must
 * include NONE (for manually-added positions with no original thesis), and the
 * prompt must instruct the model to use NONE and to write the rationale in German.
 */
class GroparPromptContractTest {

    @Test
    void thesisStatusEnumIncludesNone() {
        JsonNode schema = AgentResources.readSchema(
                JsonMapper.builder().build(), "schemas/exit-signal-list.json");
        JsonNode enumNode = schema
                .path("properties").path("signals")
                .path("items").path("properties")
                .path("thesis_status").path("enum");
        List<String> values = new ArrayList<>();
        enumNode.forEach(n -> values.add(n.asText()));
        assertThat(values)
                .as("thesis_status enum must add NONE for positions without an original thesis")
                .containsExactlyInAnyOrder("INTACT", "WEAKENING", "INVALIDATED", "NONE");
    }

    @Test
    void promptInstructsNoneAndGermanRationale() {
        String prompt = AgentResources.classpath("prompts/gropar.md");
        assertThat(prompt)
                .as("gropar prompt must define NONE thesis_status for positions without a thesis")
                .contains("NONE");
        assertThat(prompt)
                .as("gropar prompt must anchor German rationale output")
                .contains("German");
    }
}
