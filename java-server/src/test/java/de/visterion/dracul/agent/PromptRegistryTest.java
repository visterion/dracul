package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test IS the CI guard that forces a {@code prompt_registry.json} bump
 * alongside any prompt file edit: every registry entry's {@code body_hash} must
 * equal the live classpath file's hash, and every {@code version} must equal the
 * live file's {@code <!-- agent-meta -->} header version.
 */
class PromptRegistryTest {

    private static final Set<String> AGENTS = Set.of(
            "daywalker", "executor", "gropar", "strigoi-echo", "strigoi-index",
            "strigoi-insider", "strigoi-lazarus", "strigoi-merger", "strigoi-spin", "voievod");

    private final PromptRegistry registry = new PromptRegistry(new ObjectMapper());

    @Test
    void hasExactlyTheTenBundledAgents() {
        for (String agent : AGENTS) {
            assertThat(registry.entry(agent)).as("registry entry for %s", agent).isPresent();
        }
        assertThat(registry.knownHashes()).hasSize(AGENTS.size());
    }

    @Test
    void everyEntryHashMatchesTheLivePromptFile() {
        for (String agent : AGENTS) {
            var entry = registry.entry(agent).orElseThrow();
            String liveBody = PromptDocument.bodyFromClasspath("prompts/" + agent + ".md");

            assertThat(PromptHashes.hash(liveBody))
                    .as("body_hash for %s must match prompts/%s.md — bump the registry "
                            + "alongside any prompt edit", agent, agent)
                    .isEqualTo(entry.bodyHash());
        }
    }

    @Test
    void everyEntryVersionMatchesTheLiveHeaderVersion() {
        for (String agent : AGENTS) {
            var entry = registry.entry(agent).orElseThrow();
            PromptDocument doc = PromptDocument.fromClasspath("prompts/" + agent + ".md");

            assertThat(entry.version())
                    .as("version for %s must match the file's agent-meta header", agent)
                    .isEqualTo(doc.version());
        }
    }

    @Test
    void unknownAgentIsAbsent() {
        assertThat(registry.entry("ghost")).isEmpty();
    }
}
