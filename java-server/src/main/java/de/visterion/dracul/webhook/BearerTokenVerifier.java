package de.visterion.dracul.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class BearerTokenVerifier {

    private final byte[] expected;

    public BearerTokenVerifier(String expectedToken) {
        this.expected = expectedToken == null
                ? new byte[0]
                : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(String authHeader) {
        if (authHeader == null) return false;
        if (!authHeader.startsWith("Bearer ")) return false;
        byte[] presented = authHeader.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }
}
