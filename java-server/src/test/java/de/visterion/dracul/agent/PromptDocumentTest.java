package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
     * sha256sum of each prompt file's contents captured BEFORE headers were added
     * (12-hex prefix, same derivation as AgentVersionResolver.versionFor).
     */
    private static final Map<String, String> PRE_HEADER_BODY_SHA256_12 = Map.ofEntries(
            Map.entry("daywalker", "64bef970682b"),
            Map.entry("executor", "766853df4624"),
            Map.entry("gropar", "625d2484ce49"),
            Map.entry("strigoi-echo", "ad753c04c703"),
            Map.entry("strigoi-index", "46c546c67281"),
            Map.entry("strigoi-insider", "91059590df69"),
            Map.entry("strigoi-lazarus", "238a931ad45f"),
            Map.entry("strigoi-merger", "475203828483"),
            Map.entry("strigoi-spin", "9517b89a850e"),
            Map.entry("voievod", "ad44dfcc3cfd")
    );

    @Test
    void allBundledPromptsHaveValidHeaderAndStableBody() {
        for (String agent : PRE_HEADER_BODY_SHA256_12.keySet()) {
            String path = "prompts/" + agent + ".md";
            PromptDocument doc = PromptDocument.fromClasspath(path);

            assertThat(doc.agent()).as("agent for %s", path).isEqualTo(agent);
            assertThat(doc.version()).as("version for %s", path).matches("\\d+\\.\\d+\\.\\d+");
            assertThat(doc.body()).as("body for %s must not start with a header", path)
                    .doesNotStartWith("<!--");
        }
    }

    @Test
    void agentVersionUnchangedByHeader() {
        for (Map.Entry<String, String> entry : PRE_HEADER_BODY_SHA256_12.entrySet()) {
            String agent = entry.getKey();
            String expectedPrefix = entry.getValue();
            String body = PromptDocument.bodyFromClasspath("prompts/" + agent + ".md");

            assertThat(sha256Hex12(body))
                    .as("body hash for %s must equal the pre-header file hash", agent)
                    .isEqualTo(expectedPrefix);
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
