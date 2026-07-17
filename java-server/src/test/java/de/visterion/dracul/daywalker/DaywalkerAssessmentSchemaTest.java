package de.visterion.dracul.daywalker;

import de.visterion.dracul.agent.AgentResources;
import de.visterion.dracul.hunting.news.NewsEventType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the daywalker-assessment {@code event_type} enum to the code taxonomy:
 * schema enum == every NewsEventType.wireValue() plus exactly {"other", "none"}
 * (spec §4.3, R2-Min2). A taxonomy change without a schema change (or vice versa)
 * fails here instead of silently desynchronizing prompt, schema, and DB values.
 */
class DaywalkerAssessmentSchemaTest {

    @Test
    void eventTypeEnumEqualsWireValuesPlusOtherAndNone() {
        JsonNode schema = AgentResources.readSchema(new ObjectMapper(),
                "schemas/daywalker-assessment.json");
        JsonNode enumNode = schema.path("properties").path("event_type").path("enum");
        assertThat(enumNode.isArray()).as("event_type enum present").isTrue();

        List<String> schemaEnum = new ArrayList<>();
        enumNode.forEach(n -> schemaEnum.add(n.asText()));

        List<String> expected = new ArrayList<>();
        for (NewsEventType t : NewsEventType.values()) expected.add(t.wireValue());
        expected.add("other");
        expected.add("none");

        assertThat(schemaEnum).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void eventTypeIsOptional() {
        JsonNode schema = AgentResources.readSchema(new ObjectMapper(),
                "schemas/daywalker-assessment.json");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).doesNotContain("event_type");
    }
}
