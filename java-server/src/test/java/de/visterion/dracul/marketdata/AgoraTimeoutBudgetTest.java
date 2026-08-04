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
 * MCP client request timeout (dracul.agora.timeout-ms, used by {@link AgoraClient}). Since
 * the 2026-08-03 timeout split, connect and request are separate budgets
 * (dracul.agora.connect-timeout-ms is its own, shorter property) — this test only pins the
 * request-timeout side. If the Dracul default is ever lowered to 7000 ms or less, a slow news
 * feed turns Agora-side "partial results" into a total AgoraUnavailableException here —
 * this test makes that misconfiguration fail the build instead of failing in production.
 */
public class AgoraTimeoutBudgetTest {

    /** Agora's news fan-out budget; the Agora repo pins the same value on its side. */
    private static final long AGORA_NEWS_FANOUT_BUDGET_MS = 7000;

    @Test
    void agoraNewsFanoutBudgetStaysStrictlyBelowDraculMcpTimeoutDefault() throws IOException {
        assertThat(AGORA_NEWS_FANOUT_BUDGET_MS)
                .as("Agora news fan-out budget (7000 ms) must stay strictly below the "
                        + "dracul.agora.timeout-ms default in application.yaml")
                .isLessThan(configuredTimeoutDefaultMs());
    }

    // ---- Form-4 budget (R2-1) -----------------------------------------------------------
    //
    // The insider hunter timed out on EVERY run once the EFTS forms token was corrected and the
    // market-wide Form-4 result went from 42 to 1,697 hits: Agora's inner deadline was larger
    // than Dracul's outer client timeout, which it can never be. These constants mirror Agora's
    // EdgarSearchService; if Agora raises its deadline without Dracul raising its budget, this
    // test fails at build time instead of at 04:00 in production.

    /** Agora {@code EdgarSearchService.FORM4_DEADLINE_MS} — the aggregate archive-fetch deadline. */
    private static final long AGORA_FORM4_DEADLINE_MS = 30_000;

    /**
     * Two EFTS searches (caller window, then the late-filing pad) of up to
     * {@code HARD_FETCH_CAP / 100} = 10 pages each. Measured on prod 2026-08-04: one 10-page
     * search took 3.7 s (08:53:27.872 -> 08:53:31.6), so both together are ~7.9 s at the observed
     * per-page cost.
     */
    private static final long AGORA_FORM4_EFTS_PAGING_MS = 7_900;

    /** One archive GET can start just before the deadline check: THROTTLE_MS 110 + ~80 ms fetch. */
    private static final long AGORA_FORM4_TAIL_FETCH_MS = 190;

    /** MCP framing, result serialisation and transport on top of Agora's own work. */
    private static final long MCP_OVERHEAD_MS = 1_000;

    @Test
    void form4ToolBudgetExceedsAgorasOwnWorstCase() throws IOException {
        long worstCase = AGORA_FORM4_DEADLINE_MS + AGORA_FORM4_EFTS_PAGING_MS
                + AGORA_FORM4_TAIL_FETCH_MS + MCP_OVERHEAD_MS;   // 39 090 ms
        assertThat(configuredForm4TimeoutMs())
                .as("dracul.agora.tool-timeout-ms[get_form4_transactions] must exceed Agora's own "
                        + "worst case (%d ms): an inner deadline larger than the outer one always "
                        + "loses, which is exactly how strigoi-insider started timing out", worstCase)
                .isGreaterThan(worstCase);
    }

    @Test
    void form4ToolBudgetIsAnOverrideAndNotTheGlobalDefault() throws IOException {
        assertThat(configuredForm4TimeoutMs())
                .as("the Form-4 budget must be a per-tool override — raising the global default "
                        + "would license the same 45 s hang on a quote lookup")
                .isGreaterThan(configuredTimeoutDefaultMs());
    }

    /** Reads the DRACUL_AGORA_TIMEOUT_MS default straight out of the shipped application.yaml. */
    private static long configuredTimeoutDefaultMs() throws IOException {
        return yamlDefault("timeout-ms:\\s*\\$\\{DRACUL_AGORA_TIMEOUT_MS:(\\d+)}",
                "dracul.agora.timeout-ms");
    }

    /** Reads the per-tool Form-4 override default straight out of the shipped application.yaml. */
    public static long configuredForm4TimeoutMs() throws IOException {
        return yamlDefault(
                "\"\\[get_form4_transactions]\":\\s*\\$\\{DRACUL_AGORA_FORM4_TIMEOUT_MS:(\\d+)}",
                "dracul.agora.tool-timeout-ms[get_form4_transactions]");
    }

    private static long yamlDefault(String regex, String what) throws IOException {
        String yaml;
        try (InputStream in = AgoraTimeoutBudgetTest.class.getResourceAsStream("/application.yaml")) {
            assertThat(in).as("application.yaml on the test classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile(regex).matcher(yaml);
        assertThat(m.find()).as("%s default present in application.yaml", what).isTrue();
        return Long.parseLong(m.group(1));
    }
}
