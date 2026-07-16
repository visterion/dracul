package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cross-repo timing contract from the T1.1 multi-source news ingest (R3-M7):
 * Agora's news-aggregator fan-out budget is 7000 ms and MUST stay strictly below Dracul's
 * MCP client timeout (dracul.agora.timeout-ms, used by {@link AgoraClient} for connect and
 * request timeouts). If the Dracul default is ever lowered to 7000 ms or less, a slow news
 * feed turns Agora-side "partial results" into a total AgoraUnavailableException here —
 * this test makes that misconfiguration fail the build instead of failing in production.
 */
class AgoraTimeoutBudgetTest {

    /** Agora's news fan-out budget; the Agora repo pins the same value on its side. */
    private static final long AGORA_NEWS_FANOUT_BUDGET_MS = 7000;

    @Test
    void agoraNewsFanoutBudgetStaysStrictlyBelowDraculMcpTimeoutDefault() throws IOException {
        assertThat(AGORA_NEWS_FANOUT_BUDGET_MS)
                .as("Agora news fan-out budget (7000 ms) must stay strictly below the "
                        + "dracul.agora.timeout-ms default in application.yaml")
                .isLessThan(configuredTimeoutDefaultMs());
    }

    /** Reads the DRACUL_AGORA_TIMEOUT_MS default straight out of the shipped application.yaml. */
    private static long configuredTimeoutDefaultMs() throws IOException {
        String yaml;
        try (InputStream in = AgoraTimeoutBudgetTest.class.getResourceAsStream("/application.yaml")) {
            assertThat(in).as("application.yaml on the test classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("timeout-ms:\\s*\\$\\{DRACUL_AGORA_TIMEOUT_MS:(\\d+)}").matcher(yaml);
        assertThat(m.find())
                .as("dracul.agora.timeout-ms default (${DRACUL_AGORA_TIMEOUT_MS:<n>}) present in application.yaml")
                .isTrue();
        return Long.parseLong(m.group(1));
    }
}
