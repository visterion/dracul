package de.visterion.dracul.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared derivation of the short prompt-body hash used both as the runtime
 *  agent_version (see AgentVersionResolver) and as the registry body_hash
 *  (see PromptRegistry / PromptRegistryValidator). */
public final class PromptHashes {

    private PromptHashes() {}

    public static String hash(String body) {
        return "p-" + sha256Hex(body).substring(0, 12);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
