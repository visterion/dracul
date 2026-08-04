package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.agent.AgentResources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards strigoi-index's output contract against the defect that killed prod run
 * {@code 4ED119E68E1D48FEB3D23B3F652641D1} (2026-08-04):
 * {@code output_schema: /prey/0/companyName: null found, string expected} — the schema demanded a
 * non-nullable string for a field the payload can legitimately carry as null, so the model had to
 * either invent an issuer name or fail the whole run. It failed, and Vistierie discards everything
 * on the first schema violation.
 *
 * <p>The name is filled from the source feed and, for a still-listed constituent, from the index
 * membership list — but a residue stays genuinely unresolvable (an {@code add} announced before it
 * enters the index, with a press release whose prose does not yield a name). For that residue the
 * honest contract is a nullable field, and — the actual lesson of the executor {@code side} bug —
 * <b>the prompt must say exactly what the schema says.</b>
 */
class StrigoiIndexPreyContractTest {

    private static JsonNode companyNameNode() {
        return AgentResources.readSchema(JsonMapper.builder().build(), "schemas/prey-list-index.json")
                .path("properties").path("prey").path("items")
                .path("properties").path("companyName");
    }

    @Test
    void companyNameIsNullableInTheSchema() {
        JsonNode type = companyNameNode().path("type");
        List<String> types = new ArrayList<>();
        type.forEach(n -> types.add(n.asString()));
        assertThat(types)
                .as("companyName must accept null — the change feed cannot always resolve an issuer "
                        + "name, and a required non-nullable string fails the entire run")
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void companyNameStaysRequiredSoTheModelCannotSilentlyDropIt() {
        JsonNode required = AgentResources.readSchema(JsonMapper.builder().build(),
                        "schemas/prey-list-index.json")
                .path("properties").path("prey").path("items").path("required");
        List<String> fields = new ArrayList<>();
        required.forEach(n -> fields.add(n.asString()));
        assertThat(fields)
                .as("nullable, but still required: an omitted name and an unknown name must stay "
                        + "distinguishable (same shape as executor-decision's `side`)")
                .contains("companyName");
    }

    @Test
    void promptTellsTheModelToEmitNullRatherThanInventAName() {
        String prompt = AgentResources.classpath("prompts/strigoi-index.md");
        assertThat(prompt)
                .as("prompt must state the payload field is nullable")
                .contains("`companyName` — may be null");
        assertThat(prompt)
                .as("prompt must instruct emitting JSON null for an unresolved name — the schema "
                        + "and the prompt have to agree, or the executor `side` bug repeats")
                .contains("emit `companyName` as `null`");
        assertThat(prompt)
                .as("prompt must forbid inventing/deriving a name from the ticker")
                .containsIgnoringCase("never invent a company name");
    }
}
