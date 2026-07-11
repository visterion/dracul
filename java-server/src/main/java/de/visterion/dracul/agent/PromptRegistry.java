package de.visterion.dracul.agent;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads {@code prompts/prompt_registry.json} at startup: the expected
 * {@code version} + {@code body_hash} for every bundled agent prompt. Used by
 * {@link PromptRegistryValidator} to detect prompt files that changed without a
 * matching registry bump, and by the archive/versioning workflow generally.
 */
@Component
public class PromptRegistry {

    private static final String REGISTRY_PATH = "prompts/prompt_registry.json";

    public record Entry(String version, String bodyHash) {}

    private final Map<String, Entry> entries;

    public PromptRegistry(ObjectMapper mapper) {
        this.entries = load(mapper);
    }

    private static Map<String, Entry> load(ObjectMapper mapper) {
        JsonNode root = AgentResources.readSchema(mapper, REGISTRY_PATH);
        Map<String, Entry> result = new LinkedHashMap<>();
        for (var field : root.properties()) {
            JsonNode node = field.getValue();
            result.put(field.getKey(), new Entry(
                    node.path("version").asString(null),
                    node.path("body_hash").asString(null)));
        }
        return Map.copyOf(result);
    }

    public Optional<Entry> entry(String agent) {
        return Optional.ofNullable(entries.get(agent));
    }

    public Set<String> knownHashes() {
        return entries.values().stream()
                .map(Entry::bodyHash)
                .collect(Collectors.toUnmodifiableSet());
    }
}
