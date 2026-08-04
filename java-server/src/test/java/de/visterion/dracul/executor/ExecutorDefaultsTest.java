package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefaultProvider;
import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.ToolCatalogEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorDefaultsTest {

    private AgentDefaultProvider provider(String schedule) {
        return new ExecutorDefaults().executorAgentDefaults(new ObjectMapper(), schedule);
    }

    @Test
    void defaultDefinition_hasExpectedShape() {
        AgentDefinition def = provider("").defaultDefinition();

        assertThat(def.name()).isEqualTo("executor");
        assertThat(def.completionPath()).isEqualTo("/api/executor/complete");
        assertThat(def.outputSchema()).isNotNull();
        assertThat(def.promptText()).isNotNull().isNotBlank();
    }

    @Test
    void blankSchedule_isCoercedToNull() {
        assertThat(provider("").defaultDefinition().schedule()).isNull();
    }

    @Test
    void tools_areBoundInOrder() {
        AgentDefinition def = provider("").defaultDefinition();

        assertThat(def.tools()).extracting("toolName")
                .containsExactly(
                        "fetch_pending_signals",
                        "get_account",
                        "list_positions",
                        "place_entry",
                        "submit_decision",
                        "fetch_open_positions",
                        "exit_position",
                        "add_tranche");
    }

    @Test
    void providerCatalogEntries_hasEightEntriesWithCorrectCallbacks() {
        // Exercise the PROVIDER's catalogEntries() — this is what AgentToolCatalog
        // actually calls. A provider that only overrides defaultDefinition() would
        // fall back to the empty interface default and fail here.
        List<ToolCatalogEntry> entries = provider("").catalogEntries();

        assertThat(entries).hasSize(8);
        assertThat(entries).extracting("callbackPath")
                .containsExactlyInAnyOrder(
                        "/api/executor/tools/fetch-pending-signals",
                        "/api/executor/tools/get-account",
                        "/api/executor/tools/list-positions",
                        "/api/executor/tools/place-entry",
                        "/api/executor/tools/submit-decision",
                        "/api/executor/tools/fetch-open-positions",
                        "/api/executor/tools/exit-position",
                        "/api/executor/tools/add-tranche");

        ToolCatalogEntry placeEntry = entries.stream()
                .filter(e -> e.toolName().equals("place_entry"))
                .findFirst().orElseThrow();
        assertThat(placeEntry.timeoutSeconds()).isEqualTo(60);
        String requiredJson = placeEntry.inputSchema().get("required").toString();
        assertThat(requiredJson).contains("signal_id").contains("stop_price");
        // confidence is an OPTIONAL 0..1 number (the executor-side Brier/calibration input) —
        // present in properties, never required.
        assertThat(placeEntry.inputSchema().get("properties").has("confidence")).isTrue();
        assertThat(requiredJson).doesNotContain("confidence");

        ToolCatalogEntry fetchOpenPositions = entries.stream()
                .filter(e -> e.toolName().equals("fetch_open_positions"))
                .findFirst().orElseThrow();
        assertThat(fetchOpenPositions.timeoutSeconds()).isEqualTo(30);

        ToolCatalogEntry exitPosition = entries.stream()
                .filter(e -> e.toolName().equals("exit_position"))
                .findFirst().orElseThrow();
        assertThat(exitPosition.timeoutSeconds()).isEqualTo(60);
        assertThat(exitPosition.inputSchema().get("required").toString()).contains("symbol");

        ToolCatalogEntry addTranche = entries.stream()
                .filter(e -> e.toolName().equals("add_tranche"))
                .findFirst().orElseThrow();
        assertThat(addTranche.timeoutSeconds()).isEqualTo(60);
        String addTrancheRequired = addTranche.inputSchema().get("required").toString();
        assertThat(addTrancheRequired).contains("symbol").contains("reason");
    }

    /**
     * D1 — {@code decisions[].side} must accept null.
     *
     * <p>The prompt tells the model to leave {@code side} null on SKIP/HOLD/ADD_TRANCHE records,
     * while the schema declared a bare {@code "type": "string"} with a two-value enum. Vistierie
     * fails the whole run terminally on the first output-schema violation and nulls the output,
     * so one SKIP record destroyed the entire run's audit trail. Null must validate; a non-null
     * value must still be BUY or SELL.
     */
    @Test
    void outputSchema_decisionSide_isNullableButStillEnumConstrained() {
        JsonNode side = provider("").defaultDefinition().outputSchema()
                .path("properties").path("decisions").path("items").path("properties").path("side");

        assertThat(side.isMissingNode()).isFalse();
        assertThat(side.path("type").toString()).contains("string").contains("null");

        List<String> enumValues = new ArrayList<>();
        for (JsonNode v : side.path("enum")) enumValues.add(v.isNull() ? null : v.asString());
        assertThat(enumValues).containsExactlyInAnyOrder("BUY", "SELL", null);
    }

    /**
     * D2 — {@code submit_decision} must declare the {@code decisions} array it actually reads.
     *
     * <p>It was registered with the shared argument-less {@code {"type":"object","properties":{}}}
     * schema, and the model obediently called it with {@code {}}: the server recorded zero
     * decisions and every SKIP signal stayed PENDING for re-evaluation on the next run.
     *
     * <p>The schema works on the MODEL, not on the wire. Nothing validates a call's arguments
     * against it — Vistierie checks {@code input_schema} only as a schema, at agent-definition
     * time, and the sole {@code schemas.validate} call site ({@code OutputSchemaValidator:49})
     * checks the agent's OUTPUT. The bridge appends the schema to the tool description, so it is
     * effectively a prompt. Which is why a well-formed schema is necessary and not sufficient:
     * the handler has to cope with what actually arrives (see
     * {@code ExecutorWebhookController.coerceDecisions} and the stringified-array case it exists
     * for), and this assertion only pins the description side of that pair.
     */
    @Test
    void submitDecision_declaresTheDecisionsArrayItReads() {
        ToolCatalogEntry submitDecision = provider("").catalogEntries().stream()
                .filter(e -> e.toolName().equals("submit_decision"))
                .findFirst().orElseThrow();

        JsonNode schema = submitDecision.inputSchema();
        assertThat(schema.get("required").toString()).contains("decisions");

        JsonNode decisions = schema.path("properties").path("decisions");
        assertThat(decisions.path("type").asString()).isEqualTo("array");

        JsonNode itemProps = decisions.path("items").path("properties");
        assertThat(itemProps.has("signal_id")).isTrue();
        assertThat(itemProps.has("symbol")).isTrue();
        assertThat(itemProps.has("action")).isTrue();
        assertThat(itemProps.has("rationale")).isTrue();
        assertThat(itemProps.has("side")).isTrue();
        assertThat(itemProps.has("limit_price")).isTrue();
        assertThat(itemProps.has("stop_price")).isTrue();
        assertThat(itemProps.has("take_profit")).isTrue();

        // Same four actions the server branches on and the output schema declares.
        assertThat(itemProps.path("action").path("enum").toString())
                .contains("ENTER").contains("SKIP").contains("ADD_TRANCHE").contains("HOLD");
        // Mirrors the output schema: null is legal for the non-ENTER records.
        assertThat(itemProps.path("side").path("type").toString()).contains("null");
        assertThat(decisions.path("items").path("required").toString())
                .contains("signal_id").contains("symbol").contains("action").contains("rationale");
    }

    /** The two genuinely argument-less tools must KEEP the empty schema. */
    @Test
    void argumentlessTools_keepTheEmptySchema() {
        List<ToolCatalogEntry> entries = provider("").catalogEntries();
        for (String name : List.of("fetch_pending_signals", "fetch_open_positions")) {
            ToolCatalogEntry entry = entries.stream()
                    .filter(e -> e.toolName().equals(name))
                    .findFirst().orElseThrow();
            assertThat(entry.inputSchema().path("properties").isEmpty())
                    .as("%s must stay argument-less", name).isTrue();
        }
    }
}
