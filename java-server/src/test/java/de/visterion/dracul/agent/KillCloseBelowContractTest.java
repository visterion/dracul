package de.visterion.dracul.agent;

import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.webhook.PreyMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract between the two kill-level hunters' output schemas, their prompts and
 * {@link PreyMapper} (spec 2026-10-02 §3.1, §5 P3). The recurring agent bug class in this project
 * is a schema field the prompt never names (the model then never emits it, or a key allow-list
 * forbids it); this pins that every prey-item property is named, in backticks, in its prompt.
 *
 * <p>Dracul has no JSON-Schema validator dependency and must not add one (see
 * {@link NewsSentimentSchemaTest}), so "validates" is checked structurally: the declared type
 * union admits the JSON type of each sample value, and no numeric bound that would turn a slip
 * into a run-killing violation is present (Vistierie hard-fails a run on any violation).
 */
class KillCloseBelowContractTest {

    private static final Map<String, String> PROMPT_TO_SCHEMA = Map.of(
            "prompts/strigoi-echo.md", "schemas/prey-list-pead.json",
            "prompts/strigoi-lazarus.md", "schemas/prey-list-lazarus.json");

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode preyItems(String schemaPath) {
        return AgentResources.readSchema(mapper, schemaPath)
                .path("properties").path("prey").path("items");
    }

    @Test
    void everyPreyItemPropertyIsNamedInItsPrompt() {
        PROMPT_TO_SCHEMA.forEach((promptPath, schemaPath) -> {
            String prompt = AgentResources.classpath(promptPath);
            List<String> missing = new ArrayList<>();
            for (String property : preyItems(schemaPath).path("properties").propertyNames()) {
                if (!prompt.contains("`" + property + "`")) missing.add(property);
            }
            assertThat(missing).as("%s properties not named in %s", schemaPath, promptPath).isEmpty();
        });
    }

    @Test
    void killCloseBelowIsOptionalAndAdmitsNumberStringAndNull() {
        PROMPT_TO_SCHEMA.values().forEach(schemaPath -> {
            JsonNode items = preyItems(schemaPath);
            JsonNode field = items.path("properties").path(PreyMapper.KILL_CLOSE_BELOW);
            assertThat(field.isMissingNode()).as("%s declares kill_close_below", schemaPath).isFalse();

            List<String> types = new ArrayList<>();
            field.path("type").forEach(t -> types.add(t.asString()));
            assertThat(types).containsExactlyInAnyOrder("number", "string", "null");
            assertThat(field.has("minimum")).isFalse();
            assertThat(field.has("exclusiveMinimum")).isFalse();
            NewsSentimentSchemaTest.assertNotRequired(items.path("required"), PreyMapper.KILL_CLOSE_BELOW);

            // Structural validation of the samples the field must accept without failing a run.
            for (String sample : List.of("48.2", "null", "\"56,40\"")) {
                JsonNode value = AgentResources.parseJson(mapper, sample);
                assertThat(types).as("%s admits %s", schemaPath, sample).contains(jsonType(value));
            }
        });
    }

    @Test
    void hunterSchemasOutsideScopeDoNotDeclareTheLevel() {
        for (String schemaPath : List.of("schemas/prey-list-spin.json", "schemas/prey-list-index.json",
                "schemas/prey-list-merger.json", "schemas/prey-list.json")) {
            assertThat(preyItems(schemaPath).path("properties").has(PreyMapper.KILL_CLOSE_BELOW))
                    .as("%s must not offer kill_close_below (spec §3.1: out of scope)", schemaPath)
                    .isFalse();
        }
    }

    @Test
    void preyMapperReadsTheSchemaKey() {
        JsonNode prey = AgentResources.parseJson(mapper, """
                [{"symbol":"SYNCT","companyName":"Synthetic","confidence":0.7,"thesis":"t",
                  "kill_criteria":["Close below 41.30"],"kill_close_below":41.3}]
                """);
        List<Prey> mapped = new PreyMapper().map(prey, "strigoi-lazarus", "QUALITY_52W_LOW", "6m", false);
        assertThat(mapped.getFirst().killCloseBelow()).isEqualByComparingTo("41.3");
    }

    private static String jsonType(JsonNode v) {
        if (v.isNull()) return "null";
        if (v.isNumber()) return "number";
        if (v.isString()) return "string";
        return v.getNodeType().name().toLowerCase();
    }
}
