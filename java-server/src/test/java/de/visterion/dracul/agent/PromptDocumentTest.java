package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards {@link PromptDocument}'s parsing rules and the hash-stability contract:
 * prepending an agent-meta header to a prompt file must never change the body that
 * gets stored, hashed (agent_version) and sent to Vistierie.
 */
class PromptDocumentTest {

    @Test
    void parsesHeaderAndBody() {
        String raw = """
                <!-- agent-meta
                agent: strigoi-spin
                version: 1.0.0
                -->

                # Body starts here
                Some content.
                """;

        PromptDocument doc = PromptDocument.parse(raw);

        assertThat(doc.agent()).isEqualTo("strigoi-spin");
        assertThat(doc.version()).isEqualTo("1.0.0");
        assertThat(doc.body()).isEqualTo("# Body starts here\nSome content.\n");
    }

    @Test
    void bodyIsByteIdenticalToPreHeaderFile() {
        String oldContent = "# Strigoi-Spin\n\nSome original prompt body.\nMore text.\n";
        String header = """
                <!-- agent-meta
                agent: strigoi-spin
                version: 1.0.0
                -->
                """;
        String withHeader = header + "\n" + oldContent;

        PromptDocument doc = PromptDocument.parse(withHeader);

        assertThat(doc.body()).isEqualTo(oldContent);
    }

    @Test
    void noHeaderReturnsRawAsBody() {
        String raw = "# Just a normal prompt\nNo header here.\n";

        PromptDocument doc = PromptDocument.parse(raw);

        assertThat(doc.agent()).isNull();
        assertThat(doc.version()).isNull();
        assertThat(doc.body()).isEqualTo(raw);
    }

    @Test
    void malformedHeaderThrows() {
        String raw = """
                <!-- agent-meta
                agent: strigoi-spin
                version: 1.0.0
                # missing terminator
                Body text.
                """;

        assertThatThrownBy(() -> PromptDocument.parse(raw))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Expected body hashes DERIVED from {@code prompt_registry.json} — the single source of
     * truth, not a third hand-maintained copy. The "prompt body edited without a registry
     * bump" CI guard lives in {@link PromptRegistryTest#everyEntryHashMatchesTheLivePromptFile}
     * (and at runtime in {@code PromptRegistryValidator}); what THIS class adds on top is an
     * independent re-derivation of the hash (raw SHA-256 here vs {@code PromptHashes} there),
     * pinning that header stripping and the hash derivation itself behave as specified.
     */
    private static Map<String, String> registryBodyHashes() {
        JsonNode root = AgentResources.readSchema(new ObjectMapper(), "prompts/prompt_registry.json");
        Map<String, String> out = new LinkedHashMap<>();
        for (var field : root.properties()) {
            out.put(field.getKey(), field.getValue().path("body_hash").asString(null));
        }
        return out;
    }

    @Test
    void allBundledPromptsHaveValidHeaderAndStableBody() {
        Map<String, String> registry = registryBodyHashes();
        assertThat(registry).isNotEmpty();
        for (String agent : registry.keySet()) {
            String path = "prompts/" + agent + ".md";
            PromptDocument doc = PromptDocument.fromClasspath(path);

            assertThat(doc.agent()).as("agent for %s", path).isEqualTo(agent);
            assertThat(doc.version()).as("version for %s", path).matches("\\d+\\.\\d+\\.\\d+");
            assertThat(doc.body()).as("body for %s must not start with a header", path)
                    .doesNotStartWith("<!--");
        }
    }

    @Test
    void registryBodyHashIsThePreHeaderBodySha256() {
        for (Map.Entry<String, String> entry : registryBodyHashes().entrySet()) {
            String agent = entry.getKey();
            String body = PromptDocument.bodyFromClasspath("prompts/" + agent + ".md");

            assertThat("p-" + sha256Hex12(body))
                    .as("registry body_hash for %s must equal the pre-header body hash "
                            + "(independent SHA-256 derivation)", agent)
                    .isEqualTo(entry.getValue());
        }
    }

    private static String sha256Hex12(String s) {
        try {
            String full = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
            return full.substring(0, 12);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
