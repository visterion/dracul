package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-shape guard for the single "search" mcp catalog entry (fix D9).
 *
 * <p>Production incident (agent strigoi-index, 2026-08): the model invented
 * {@code where.symbol} because the advertised {@code where} was an untyped object with no
 * properties. HiveMem's {@code CellSelector} allow-list is exactly
 * {@code realm, realm_in, signal, topic, tags, query, status} and rejects anything else with
 * "Unknown where field"; {@code SearchToolHandler} additionally rejects {@code where.query} for
 * the {@code search} tool. Dracul writes the ticker as the cell {@code topic}
 * (HiveMemResearchService), so {@code topic} is THE ticker filter.
 *
 * <p>These assertions pin: (a) every advertised key is one HiveMem accepts for {@code search},
 * (b) {@code topic} is advertised and its description names the ticker, (c) {@code query} is
 * never advertised, and (d) {@code symbol} is explicitly called out as non-existent so the model
 * does not re-invent it.
 */
class MemorySearchCatalogContributorTest {

    /** Exactly CellSelector.ALLOWED_KEYS minus "query" (rejected by SearchToolHandler for search). */
    private static final List<String> HIVEMEM_SEARCH_WHERE_KEYS =
            List.of("realm", "realm_in", "signal", "topic", "tags", "status");

    private ToolCatalogEntry entry() {
        var entries = new MemorySearchCatalogContributor(JsonMapper.builder().build()).catalogEntries();
        assertThat(entries).hasSize(1);
        return entries.getFirst();
    }

    private JsonNode whereSchema() {
        return entry().inputSchema().path("properties").path("where");
    }

    private static List<String> names(JsonNode objectNode) {
        var out = new ArrayList<String>();
        objectNode.propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void contributesExactlyTheSearchToolWithWhereRequired() {
        var entry = entry();
        assertThat(entry.toolName()).isEqualTo("search");
        assertThat(entry.callbackPath()).isNull();
        var schema = entry.inputSchema();
        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("where", "limit");
        assertThat(schema.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("where");
        assertThat(schema.path("properties").path("limit").path("type").asString())
                .isEqualTo("integer");
    }

    @Test
    void whereIsATypedObjectWhosePropertiesAreAllAcceptedByHiveMem() {
        var where = whereSchema();
        assertThat(where.path("type").asString()).isEqualTo("object");
        var declared = names(where.path("properties"));
        assertThat(declared).isNotEmpty();
        assertThat(declared)
                .as("every advertised where key must be accepted by HiveMem's search tool")
                .isSubsetOf(HIVEMEM_SEARCH_WHERE_KEYS);
        assertThat(where.path("additionalProperties").asBoolean(true))
                .as("unknown where keys are a hard HiveMem error — do not allow them")
                .isFalse();
    }

    @Test
    void neverAdvertisesQueryInsideWhere() {
        assertThat(names(whereSchema().path("properties")))
                .as("SearchToolHandler rejects where.query for the search tool")
                .doesNotContain("query");
    }

    @Test
    void advertisesTheTickerFilterAsTopic() {
        var props = whereSchema().path("properties");
        assertThat(names(props)).contains("realm", "topic", "tags", "signal", "status");
        assertThat(props.path("topic").path("type").asString()).isEqualTo("string");
        assertThat(props.path("topic").path("description").asString().toLowerCase())
                .as("the topic description must make the ticker filter obvious")
                .contains("ticker");
        assertThat(props.path("realm").path("description").asString())
                .contains("dracul-research");
        assertThat(props.path("tags").path("type").asString()).isEqualTo("array");
        assertThat(props.path("tags").path("items").path("type").asString()).isEqualTo("string");
        assertThat(whereSchema().path("required").valueStream().map(JsonNode::asString).toList())
                .contains("realm");
    }

    @Test
    void statusIsConstrainedToHiveMemsAllowedValues() {
        assertThat(whereSchema().path("properties").path("status").path("enum")
                .valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("committed", "pending", "rejected");
    }

    @Test
    void toolDescriptionNamesTopicAndDeniesSymbol() {
        var description = entry().defaultDescription();
        assertThat(description).contains("dracul-research");
        assertThat(description).contains("where.topic");
        assertThat(description)
                .as("the model invented where.symbol — say plainly that it does not exist")
                .contains("symbol");
    }

    /**
     * A tool description is a factual claim about the data, and this one was not true. It told
     * the model that Dracul tags cells with the cell KIND, naming {@code "outcome"} as the
     * example. The real tag distribution in HiveMem's {@code dracul-research} realm is
     * {@code daywalker_alert 107, strigoi-echo 37, prey 37, exit_signal 13, gropar 13} —
     * <b>zero</b> cells tagged {@code outcome}, because {@code writeOutcomeCell} has never fired.
     * Advertising a tag that matches nothing teaches the model to filter its own memory down to
     * an empty result and conclude there is no history.
     */
    @Test
    void tagsDescriptionDoesNotAdvertiseATagThatNoCellCarries() {
        var tags = whereSchema().path("properties").path("tags").path("description").asString();
        assertThat(tags)
                .as("no cell in dracul-research is tagged \"outcome\" — writeOutcomeCell has "
                        + "never run, so advertising it produces guaranteed-empty filters")
                .doesNotContain("\"outcome\"");
    }

    /**
     * {@code topic} matches EXACTLY, and 26 of the 157 topics in the realm carry an exchange
     * suffix ({@code VOW3.DE}, {@code 1299.HK}, {@code 9984.T}). A description whose only example
     * is {@code "AAPL"} teaches the model to strip the suffix and miss every non-US symbol's own
     * history.
     */
    @Test
    void topicDescriptionShowsThatSuffixedTickersAreMatchedExactly() {
        var topic = whereSchema().path("properties").path("topic").path("description").asString();
        assertThat(topic)
                .as("the description must show a suffixed ticker, not only AAPL — matching is exact")
                .containsPattern("[A-Z0-9]+\\.[A-Z]{1,2}\\b");
    }

    @Test
    void whereSchemaWarnsThatSymbolIsNotAField() {
        var where = whereSchema();
        var text = where.path("description").asString()
                + where.path("properties").path("topic").path("description").asString();
        assertThat(text.toLowerCase())
                .as("either the where description or the topic description must deny 'symbol'")
                .contains("symbol");
    }
}
