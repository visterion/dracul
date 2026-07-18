package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the byte-identical shared sentiment-rubric block (T1.5 spec §4.1/§7) across the three
 * prompts that fold financial-sentiment scoring into their existing runs. The prompt system has
 * no include mechanism, so the block is duplicated verbatim into daywalker.md/renfield.md/
 * strigoi-echo.md and delimited with explicit sentinel comment markers; this test extracts the
 * sentinel-delimited substring from each file and asserts 3-way equality plus exactly one
 * sentinel pair per file. The sentinels are part of the hashed, LLM-visible prompt body
 * (PromptDocument strips only the leading agent-meta header) — intentional, do not "clean up"
 * after hashing.
 */
class SentimentRubricParityTest {

    private static final String START = "<!-- SENTIMENT-RUBRIC START -->";
    private static final String END = "<!-- SENTIMENT-RUBRIC END -->";
    private static final List<String> AGENTS = List.of("daywalker", "renfield", "strigoi-echo");

    @Test
    void rubricBlockIsByteIdenticalAcrossAllThreePrompts() {
        String reference = null;
        for (String agent : AGENTS) {
            String block = extractBlock(AgentResources.classpath("prompts/" + agent + ".md"), agent);
            if (reference == null) {
                reference = block;
            } else {
                assertThat(block).as("rubric block in %s.md must be byte-identical to daywalker.md", agent)
                        .isEqualTo(reference);
            }
        }
    }

    @Test
    void everyPromptHasExactlyOneSentinelPair() {
        for (String agent : AGENTS) {
            String prompt = AgentResources.classpath("prompts/" + agent + ".md");
            assertThat(countOccurrences(prompt, START)).as("%s.md START sentinel count", agent).isEqualTo(1);
            assertThat(countOccurrences(prompt, END)).as("%s.md END sentinel count", agent).isEqualTo(1);
        }
    }

    private static String extractBlock(String prompt, String agent) {
        int start = prompt.indexOf(START);
        int end = prompt.indexOf(END);
        assertThat(start).as("%s.md must contain the START sentinel", agent).isGreaterThanOrEqualTo(0);
        assertThat(end).as("%s.md must contain the END sentinel", agent).isGreaterThan(start);
        return prompt.substring(start, end + END.length());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
