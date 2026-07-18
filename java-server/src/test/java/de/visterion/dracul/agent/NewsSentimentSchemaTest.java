package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural back-compat guard for the additive {@code news_sentiment} array (T1.5, spec §7).
 * Dracul has NO JSON-Schema validator dependency and must not add one, so these assertions
 * inspect the raw JSON structure directly: item shape, numeric bounds, that the field (and its
 * per-item keys) never appear in a {@code required} array, and that no {@code multipleOf} was
 * added (Vistierie hard-fails a run on any output-schema violation; a binary-float multipleOf
 * would reject legitimate one-decimal sentiment values). Runtime bound-enforcement is
 * Vistierie's concern, verified post-deploy via the transcript, not here.
 */
class NewsSentimentSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void daywalkerNewsSentimentIsTopLevelAdditiveArray() {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/daywalker-assessment.json");
        assertNewsSentimentItemShape(schema.path("properties").path("news_sentiment"));
        assertNotRequired(schema.path("required"), "news_sentiment");
    }

    @Test
    void renfieldNewsSentimentIsAdditiveInsideEachProposal() {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/renfield-review.json");
        JsonNode proposalItems = schema.path("properties").path("proposals").path("items");
        assertNewsSentimentItemShape(proposalItems.path("properties").path("news_sentiment"));
        assertNotRequired(proposalItems.path("required"), "news_sentiment");
    }

    // --- shared assertions, reused by the renfield/echo cases added in Tasks 3/4 ---

    static void assertNewsSentimentItemShape(JsonNode newsSentiment) {
        assertThat(newsSentiment.path("type").asText()).isEqualTo("array");
        JsonNode items = newsSentiment.path("items");
        assertThat(items.path("type").asText()).isEqualTo("object");

        List<String> required = new ArrayList<>();
        items.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).containsExactlyInAnyOrder("headline", "sentiment");

        JsonNode sentiment = items.path("properties").path("sentiment");
        assertThat(sentiment.path("type").asText()).isEqualTo("number");
        assertThat(sentiment.path("minimum").asDouble()).isEqualTo(-1.0);
        assertThat(sentiment.path("maximum").asDouble()).isEqualTo(1.0);
        assertThat(sentiment.has("multipleOf"))
                .as("one-decimal guidance must stay prompt-only, never multipleOf")
                .isFalse();

        JsonNode headline = items.path("properties").path("headline");
        assertThat(headline.path("type").asText()).isEqualTo("string");
    }

    static void assertNotRequired(JsonNode requiredArray, String field) {
        List<String> required = new ArrayList<>();
        requiredArray.forEach(n -> required.add(n.asText()));
        assertThat(required).as("%s must never be required", field).doesNotContain(field);
    }
}
