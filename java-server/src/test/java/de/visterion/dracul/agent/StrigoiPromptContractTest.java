package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the empty-result contract in every Strigoi prompt. Root cause of the
 * recurring {@code output_schema: required property 'prey' not found} run
 * failures (echo/insider): when the screener returns zero candidates the LLM
 * improvised a non-conforming output (prose / a custom "no_signals" object)
 * instead of {@code {"prey": []}}. Every Strigoi prompt must instruct the model
 * to always return an (possibly empty) {@code prey} array.
 */
class StrigoiPromptContractTest {

    private static final List<String> STRIGOI = List.of(
            "strigoi-spin", "strigoi-merger", "strigoi-insider",
            "strigoi-echo", "strigoi-lazarus", "strigoi-index");

    @Test
    void everyStrigoiPromptDeclaresEmptyResultContract() {
        for (String name : STRIGOI) {
            String prompt = AgentResources.classpath("prompts/" + name + ".md");
            assertThat(prompt)
                    .as("%s prompt must tell the model to return {\"prey\": []} when there are no candidates", name)
                    .contains("{\"prey\": []}");
        }
    }

    @Test
    void insiderPromptDocumentsFilerRole() {
        String prompt = AgentResources.classpath("prompts/strigoi-insider.md");
        assertThat(prompt).as("insider prompt must document the per-filer role field").contains("role");
        assertThat(prompt).contains("officer title");
    }

    @Test
    void lazarusPromptRanksByFScore() {
        String prompt = AgentResources.classpath("prompts/strigoi-lazarus.md");
        assertThat(prompt).as("lazarus prompt must use the real fScore").contains("fScore");
        assertThat(prompt).contains("fScoreCriteriaAvailable");
    }

    @Test
    void lazarusPromptIsRegionNeutral() {
        String prompt = AgentResources.classpath("prompts/strigoi-lazarus.md");
        assertThat(prompt)
                .as("lazarus prompt must be region/provider-neutral: no US-specific vocabulary")
                .doesNotContain("SEC")
                .doesNotContain("Finnhub")
                .doesNotContain("XBRL");
    }

    @Test
    void mergerPromptDocumentsServerExtractedDealTerms() {
        String prompt = AgentResources.classpath("prompts/strigoi-merger.md");
        assertThat(prompt).as("merger prompt must document the server-extracted deal terms")
                .contains("offerPrice").contains("considerationType")
                .contains("exchangeRatio").contains("breakFee").contains("spreadPercent");
        assertThat(prompt).as("merger prompt must instruct preferring spreadPercent over recomputation")
                .contains("prefer");
    }

    @Test
    void spinPromptDocumentsServerExtractedDistributionTerms() {
        String prompt = AgentResources.classpath("prompts/strigoi-spin.md");
        assertThat(prompt).as("spin prompt must document the server-extracted distribution terms")
                .contains("distributionRatio").contains("recordDate").contains("distributionDate");
        assertThat(prompt).as("spin prompt must instruct falling back to termSheet when null")
                .contains("fall back to reading");
    }

    @Test
    void echoPromptUsesRevisionsDirectionNotGuidanceDirection() {
        String prompt = AgentResources.classpath("prompts/strigoi-echo.md");
        assertThat(prompt)
                .as("echo prompt must reference the renamed field")
                .contains("netEstimateRevisionsDirection");
        assertThat(prompt)
                .as("the misleading 'guidanceDirection' field name must be gone")
                .doesNotContain("guidanceDirection");
    }

    @Test
    void echoPromptScoresRecentNewsInsteadOfReGating() {
        String prompt = AgentResources.classpath("prompts/strigoi-echo.md");
        assertThat(prompt)
                .as("echo prompt must document the recentNews field surfaced via fetch_recent_pead_candidates")
                .contains("recentNews");
        assertThat(prompt)
                .as("echo prompt must instruct scoring, not re-gating, on recentNews")
                .contains("do NOT re-gate or veto");
        assertThat(prompt)
                .as("the stale 'you will not see those' framing must be gone now that recentNews is surfaced")
                .doesNotContain("you will not see those");
    }
}
