package de.visterion.dracul.webhook;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BearerTokenVerifierTest {

    private final BearerTokenVerifier verifier = new BearerTokenVerifier("expected-secret-xyz");

    @Test
    void acceptsValidBearer() {
        assertThat(verifier.verify("Bearer expected-secret-xyz")).isTrue();
    }

    @Test
    void rejectsWrongToken() {
        assertThat(verifier.verify("Bearer wrong-secret")).isFalse();
    }

    @Test
    void rejectsMissingHeader() {
        assertThat(verifier.verify(null)).isFalse();
        assertThat(verifier.verify("")).isFalse();
    }

    @Test
    void rejectsHeaderWithoutBearerPrefix() {
        assertThat(verifier.verify("expected-secret-xyz")).isFalse();
        assertThat(verifier.verify("Token expected-secret-xyz")).isFalse();
    }
}
