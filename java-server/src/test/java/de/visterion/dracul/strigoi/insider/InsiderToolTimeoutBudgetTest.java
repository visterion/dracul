package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.marketdata.AgoraTimeoutBudgetTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enclosing limit for the Form-4 budget. {@code fetch_recent_clusters} is an HTTP tool:
 * Vistierie calls Dracul's webhook, Dracul calls Agora's {@code get_form4_transactions}, so the
 * tool's declared timeout must be strictly larger than the Agora budget the same request spends
 * inside itself. Raising the inner budget without this one would just move the failure outward.
 *
 * <p>(As of 2026-08-04 Vistierie's HTTP tool path does not actually enforce
 * {@code webhook_timeout_seconds} — {@code ToolDispatcher.callOnce} uses a RestClient with no read
 * timeout, and the configured default is applied to the MCP path only. The declared value is
 * therefore documentation today; it must still be right, because the day it starts being enforced
 * must not be the day the insider hunter breaks again.)
 */
class InsiderToolTimeoutBudgetTest {

    @Test
    void fetchToolTimeoutExceedsTheAgoraForm4Budget() throws IOException {
        long form4BudgetMs = AgoraTimeoutBudgetTest.configuredForm4TimeoutMs();
        assertThat(InsiderDefaults.FETCH_TIMEOUT_SECONDS * 1000L)
                .as("the fetch_recent_clusters webhook timeout must exceed the "
                        + "get_form4_transactions Agora budget (%d ms)", form4BudgetMs)
                .isGreaterThan(form4BudgetMs);
    }

    /** Vistierie's per-agent run budget for strigoi-insider on prod is 1800 s. */
    @Test
    void fetchToolTimeoutStaysWellInsideTheAgentRunBudget() {
        assertThat(InsiderDefaults.FETCH_TIMEOUT_SECONDS).isLessThan(1800);
    }
}
