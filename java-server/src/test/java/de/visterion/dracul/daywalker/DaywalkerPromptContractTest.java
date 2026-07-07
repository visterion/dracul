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
        assertThat(p).contains("position");
        assertThat(p).contains("breached_level");
        assertThat(p).contains("position_id");
        assertThat(p).contains("CRITICAL");
    }
}
