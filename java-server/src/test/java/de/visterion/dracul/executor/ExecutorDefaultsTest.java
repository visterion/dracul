package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefaultProvider;
import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.ToolCatalogEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
}
