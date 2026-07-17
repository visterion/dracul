package de.visterion.dracul.daywalker;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class DaywalkerPromptContractTest {

    private String prompt() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("prompts/daywalker.md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void mentionsPositionContextAndBreach() throws Exception {
        String p = prompt();
        assertThat(p).as("position-levels list").contains("active_stop");
        assertThat(p).as("breach signal field").contains("breached_level");
        assertThat(p).as("position id echo").contains("position_id");
        assertThat(p).as("breach = CRITICAL by default rule").contains("by default");
        assertThat(p).as("downgrade caveat").contains("downgrade to WARNING");
    }

    @Test void mentionsEventTypeContract() throws Exception {
        String p = prompt();
        assertThat(p).as("rule-tag hint field").contains("event_tags");
        assertThat(p).as("event_type output field").contains("`event_type`");
        assertThat(p).as("'other' semantics").contains("`other`");
        assertThat(p).as("'none' semantics").contains("`none`");
    }

    @Test void mentionsDirectionWeightSectorAndMacroPortfolio() throws Exception {
        String p = prompt();
        assertThat(p).as("direction rule").contains("RELATIVE to `direction`");
        assertThat(p).as("weight scales materiality").contains("weight_pct");
        assertThat(p).as("sector field").contains("`sector`");
        assertThat(p).as("macro trigger section").contains("MACRO_PORTFOLIO");
        assertThat(p).as("snapshot payload field").contains("portfolio_snapshot");
        assertThat(p).as("portfolio pseudo-symbol echo").contains("`PORTFOLIO`");
    }
}
