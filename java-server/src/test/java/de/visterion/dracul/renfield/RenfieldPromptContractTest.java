package de.visterion.dracul.renfield;

import de.visterion.dracul.agent.AgentResources;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renfield-only guard against the sentiment rubric creating spurious {@code hold} proposals
 * (T1.5 spec §4.2/§5.2, R2 correction): the guard sentence lives OUTSIDE the shared
 * byte-identical rubric block (renfield is the only agent with a proposals[] shape to guard),
 * so it must appear after the sentinel-delimited block, not inside it.
 */
class RenfieldPromptContractTest {

    private static final String END_SENTINEL = "<!-- SENTIMENT-RUBRIC END -->";
    private static final String GUARD = "Do NOT create a proposal solely to carry a sentiment score";

    @Test
    void guardSentenceExistsOutsideTheSharedRubricBlock() {
        String prompt = AgentResources.classpath("prompts/renfield.md");
        int guardIndex = prompt.indexOf(GUARD);
        int endSentinelIndex = prompt.indexOf(END_SENTINEL);

        assertThat(guardIndex).as("renfield.md must carry the spurious-proposal guard").isGreaterThanOrEqualTo(0);
        assertThat(endSentinelIndex).as("renfield.md must carry the shared rubric block").isGreaterThanOrEqualTo(0);
        assertThat(guardIndex)
                .as("the guard is renfield-only and must stay OUTSIDE the shared byte-identical block")
                .isGreaterThan(endSentinelIndex);
    }
}
