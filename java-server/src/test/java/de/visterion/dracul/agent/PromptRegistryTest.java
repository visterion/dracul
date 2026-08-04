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
            "daywalker", "daywalker-deep", "executor", "gropar", "renfield", "strigoi-echo",
            "strigoi-index", "strigoi-insider", "strigoi-lazarus", "strigoi-merger", "strigoi-spin",
            "voievod", "voievod-outcome");

    private final PromptRegistry registry = new PromptRegistry(new ObjectMapper());

    @Test
    void hasExactlyTheThirteenBundledAgents() {
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

    /**
     * The hardcoded {@link #AGENTS} list above cannot notice a prompt file that was ADDED without
     * a registry entry — it would simply never be looked at, and {@link PromptRegistryValidator}
     * would then log "no entry for enabled agent" once, in production, at boot. So the set is also
     * derived from the shipped prompt directory and compared both ways.
     */
    @Test
    void everyShippedPromptFileHasARegistryEntryAndViceVersa() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources/prompts");
        assertThat(dir).as("bundled prompt directory").exists();
        Set<String> onDisk;
        try (var files = java.nio.file.Files.list(dir)) {
            onDisk = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        assertThat(onDisk)
                .as("prompts/*.md and the hardcoded agent list must not drift apart")
                .isEqualTo(AGENTS);
        for (String agent : onDisk) {
            assertThat(registry.entry(agent))
                    .as("prompts/%s.md ships without a prompt_registry.json entry — drift "
                            + "detection would be off for it", agent)
                    .isPresent();
        }
    }

    @Test
    void unknownAgentIsAbsent() {
        assertThat(registry.entry("ghost")).isEmpty();
    }
}
