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
 * prompt must instruct the model to use NONE, to write the rationale in German,
 * and to always wrap signals in a JSON object envelope (never a bare array).
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

    @Test
    void promptDeclaresRFrameworkRules() {
        String prompt = AgentResources.classpath("prompts/gropar.md");
        assertThat(prompt)
                .as("prompt must define the INITIAL_STOP rule")
                .contains("INITIAL_STOP");
        assertThat(prompt)
                .as("prompt must define the GIVEBACK rule")
                .contains("GIVEBACK");
        assertThat(prompt)
                .as("prompt must explain gain in R")
                .contains("gain_in_R");
    }

    @Test
    void promptInstructsObjectEnvelopeNotBareArray() {
        String prompt = AgentResources.classpath("prompts/gropar.md");
        assertThat(prompt)
                .as("prompt must demand a single object wrapping the signals array")
                .contains("single JSON object");
        assertThat(prompt)
                .as("prompt must show the empty-result skeleton")
                .contains("{\"signals\": []}");
        assertThat(prompt)
                .as("prompt must explicitly forbid a bare top-level array")
                .contains("Never return a bare array");
    }

    @Test
    void promptMentionsOverextension() {
        String prompt = AgentResources.classpath("prompts/gropar.md");
        assertThat(prompt)
                .as("gropar prompt must reference the distToMa200InAtr indicator")
                .contains("distToMa200InAtr");
        assertThat(prompt)
                .as("gropar prompt must define overextension guidance in German")
                .containsIgnoringCase("überdehnt");
    }

    @Test void promptMentionsScaleOutLadder() {
        String prompt = AgentResources.classpath("prompts/gropar.md");
        assertThat(prompt).contains("profitTargets");
        assertThat(prompt).contains("+4R");
    }
}
