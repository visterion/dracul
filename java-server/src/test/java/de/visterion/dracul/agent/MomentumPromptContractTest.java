package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** strigoi-momentum prompt <-> schema <-> tool-payload contract (prompt/schema drift class). */
class MomentumPromptContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String prompt = AgentResources.classpath("prompts/strigoi-momentum.md");
    private final JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-momentum.json");

    @Test
    void everyVetoPropertyIsNamedInThePrompt() {
        List<String> missing = new ArrayList<>();
        for (String property : schema.path("properties").path("vetoes").path("items")
                .path("properties").propertyNames()) {
            if (!prompt.contains("`" + property + "`")) missing.add(property);
        }
        assertThat(missing).isEmpty();
    }

    /** Every fact the ranking service emits per offered name is explained to the LLM. */
    @Test
    void everyOfferedFactIsNamedInThePrompt() {
        List<String> missing = new ArrayList<>();
        for (String fact : List.of("rank", "symbol", "company_name", "sector", "momentum_12_1_pct",
                "last_close", "return_1m_pct", "market_cap_millions", "held_momentum",
                "held_elsewhere", "pending_signal", "possible_corporate_action",
                "worst_1d_return_pct", "rebalance_due", "top", "refill", "held", "suspects")) {
            if (!prompt.contains("`" + fact + "`")) missing.add(fact);
        }
        assertThat(missing).isEmpty();
    }

    /** Every field {@code MomentumRankingService} emits on the {@code held} entries, plus the
     *  two informational-only fields ({@code rebalance_missed}, {@code data_source_health.partial})
     *  it can carry elsewhere — the recurring prompt/schema drift bug class (a field the tool
     *  returns that no prompt sentence ever names). */
    @Test
    void everyHeldEntryFieldAndEveryInformationalFlagIsNamedInThePrompt() {
        List<String> missing = new ArrayList<>();
        for (String field : List.of("entry_filled", "rebalance_exit_pending", "unranked_reason",
                "rebalance_missed", "partial")) {
            if (!prompt.contains("`" + field + "`")) missing.add(field);
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void promptNamesTheToolTheEmptyAnswerAndNoKillLevel() {
        assertThat(prompt).contains("`fetch_momentum_ranking`")
                .contains("{\"prey\": [], \"vetoes\": []}")
                .contains("{\"prey\": []}")
                .doesNotContain("kill_close_below");
    }

    @Test
    void onlyPreyIsRequiredAndAVetoNeedsSymbolAndReason() {
        assertThat(schema.path("required").toString()).isEqualTo("[\"prey\"]");
        assertThat(schema.path("properties").path("vetoes").path("items").path("required").toString())
                .isEqualTo("[\"symbol\",\"reason\"]");
    }
}
