package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the byte-identical shared memory-rubric block (T1.6 spec §10) across the eight
 * tool-callback prompts that gain a native {@code search} tool. The prompt system has no include
 * mechanism, so the block is duplicated verbatim into each of the eight prompts and delimited
 * with explicit sentinel comment markers; this test extracts the sentinel-delimited substring
 * from each file and asserts 8-way equality plus exactly one sentinel pair per file, and that the
 * MEMORY-RUBRIC block does not accidentally swallow or duplicate the pre-existing
 * SENTIMENT-RUBRIC block (strigoi-echo.md carries both, non-overlapping). The sentinels are part
 * of the hashed, LLM-visible prompt body (PromptDocument strips only the leading agent-meta
 * header) — intentional, do not "clean up" after hashing.
 */
class MemoryRubricParityTest {

    private static final String START = "<!-- MEMORY-RUBRIC START -->";
    private static final String END = "<!-- MEMORY-RUBRIC END -->";
    private static final List<String> AGENTS = List.of(
            "strigoi-echo", "strigoi-lazarus", "strigoi-insider", "strigoi-index",
            "strigoi-merger", "strigoi-spin", "gropar", "voievod");

    @Test
    void rubricBlockIsByteIdenticalAcrossAllEightPrompts() {
        String reference = null;
        for (String agent : AGENTS) {
            String block = extractBlock(AgentResources.classpath("prompts/" + agent + ".md"), agent);
            if (reference == null) reference = block;
            else assertThat(block).as("rubric block in %s.md", agent).isEqualTo(reference);
        }
    }

    @Test
    void everyPromptHasExactlyOneSentinelPairAndNoSentimentSubstringInside() {
        for (String agent : AGENTS) {
            String prompt = AgentResources.classpath("prompts/" + agent + ".md");
            assertThat(countOccurrences(prompt, START)).as("%s.md START count", agent).isEqualTo(1);
            assertThat(countOccurrences(prompt, END)).as("%s.md END count", agent).isEqualTo(1);
            String block = extractBlock(prompt, agent);
            assertThat(block).as("%s.md MEMORY-RUBRIC must not contain SENTIMENT-RUBRIC", agent)
                    .doesNotContain("SENTIMENT-RUBRIC");
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
