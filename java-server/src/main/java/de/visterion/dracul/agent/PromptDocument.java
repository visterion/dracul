package de.visterion.dracul.agent;

/**
 * A prompt file = optional agent-meta header + body. The body is what gets stored,
 * hashed (agent_version) and sent to Vistierie — headers never affect it.
 */
public record PromptDocument(String agent, String version, String body) {

    private static final String HEADER_START = "<!-- agent-meta\n";
    private static final String HEADER_TERMINATOR = "-->\n\n";

    public static PromptDocument parse(String raw) {
        if (!raw.startsWith(HEADER_START)) {
            return new PromptDocument(null, null, raw);
        }

        int terminatorIndex = raw.indexOf("-->");
        if (terminatorIndex < 0) {
            throw new IllegalStateException(
                    "malformed agent-meta header: no '-->' terminator found");
        }

        String headerBlock = raw.substring(HEADER_START.length(), terminatorIndex);
        String agent = null;
        String version = null;
        for (String line : headerBlock.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            switch (key) {
                case "agent" -> agent = value;
                case "version" -> version = value;
                default -> { /* ignore unknown keys */ }
            }
        }

        int bodyStart = terminatorIndex + HEADER_TERMINATOR.length();
        String body = raw.substring(bodyStart);
        return new PromptDocument(agent, version, body);
    }

    public static PromptDocument fromClasspath(String path) {
        return parse(AgentResources.classpath(path));
    }

    public static String bodyFromClasspath(String path) {
        return fromClasspath(path).body();
    }
}
