package de.visterion.dracul.executor;

import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.ToolCatalogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorDefaultsTest {

    @Test
    void defaultDefinition_hasExpectedShape() {
        var defaults = new ExecutorDefaults();
        AgentDefinition def = defaults.executorAgentDefaults("").defaultDefinition();

        assertThat(def.name()).isEqualTo("executor");
        assertThat(def.completionPath()).isEqualTo("/api/executor/complete");
        assertThat(def.outputSchema()).isNotNull();
        assertThat(def.promptText()).isNotNull().isNotBlank();
    }

    @Test
    void blankSchedule_isCoercedToNull() {
        var defaults = new ExecutorDefaults();
        AgentDefinition def = defaults.executorAgentDefaults("").defaultDefinition();

        assertThat(def.schedule()).isNull();
    }

    @Test
    void tools_areBoundInOrder() {
        var defaults = new ExecutorDefaults();
        AgentDefinition def = defaults.executorAgentDefaults("").defaultDefinition();

        assertThat(def.tools()).extracting("toolName")
                .containsExactly(
                        "fetch_pending_signals",
                        "get_account",
                        "list_positions",
                        "place_entry",
                        "submit_decision");
    }

    @Test
    void catalogEntries_hasFiveEntriesWithCorrectCallbacks() {
        List<ToolCatalogEntry> entries = ExecutorDefaults.catalogEntries();

        assertThat(entries).hasSize(5);
        assertThat(entries).extracting("callbackPath")
                .containsExactlyInAnyOrder(
                        "/api/executor/tools/fetch-pending-signals",
                        "/api/executor/tools/get-account",
                        "/api/executor/tools/list-positions",
                        "/api/executor/tools/place-entry",
                        "/api/executor/tools/submit-decision");

        ToolCatalogEntry placeEntry = entries.stream()
                .filter(e -> e.toolName().equals("place_entry"))
                .findFirst().orElseThrow();
        assertThat(placeEntry.timeoutSeconds()).isEqualTo(60);
        String requiredJson = placeEntry.inputSchema().get("required").toString();
        assertThat(requiredJson).contains("signal_id").contains("stop_price");
    }
}
