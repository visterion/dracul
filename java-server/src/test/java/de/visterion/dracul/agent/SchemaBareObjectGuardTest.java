package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the Vistierie claude-bridge crash class diagnosed 2026-10-06 (see
 * docs/wie-dracul-entscheidet.md and the strigoi-tech/strigoi-momentum silent-empty-run
 * incident): a JSON-Schema node with {@code "type": "object"} and no {@code "properties"}
 * key is turned into a bare {@code z.record(...)} by the bridge's JSON-schema driver. Since
 * the zod 4.5.4 bump (Vistierie #81, 2026-09-29), that shape crashes the in-process MCP
 * server's {@code tools/list}, so the agent gets NO tools at all, writes its tool calls as
 * plain text, and the run ends green with an empty result and zero {@code run_tool_calls}.
 *
 * <p>The interim Dracul-side workaround is to always declare {@code "properties": {}} on an
 * object node, even when there is nothing to declare (this still allows arbitrary keys, since
 * {@code additionalProperties} is left unset). This test walks every schema under
 * {@code resources/schemas/} and fails on the first node that would reproduce the crash,
 * so the next schema change cannot reintroduce the pattern silently.
 */
class SchemaBareObjectGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noObjectNodeInAnySchemaIsMissingProperties() throws Exception {
        List<String> violations = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:schemas/*.json");
        assertThat(resources).as("schemas/ resource lookup must find the schema files").isNotEmpty();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            try (var in = resource.getInputStream()) {
                JsonNode root = mapper.readTree(in);
                walk(root, "$", filename, violations);
            }
        }

        assertThat(violations)
                .as("object nodes without 'properties' crash the claude-bridge tools/list "
                        + "call under zod 4.5.4 (bare z.record) -- add \"properties\": {} instead")
                .isEmpty();
    }

    private static void walk(JsonNode node, String path, String filename, List<String> violations) {
        if (node.isObject()) {
            if ("object".equals(node.path("type").asString(null)) && !node.has("properties")) {
                violations.add(filename + " " + path);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                walk(entry.getValue(), path + "." + entry.getKey(), filename, violations);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", filename, violations);
            }
        }
    }
}
