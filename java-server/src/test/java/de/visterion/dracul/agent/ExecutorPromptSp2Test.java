package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorPromptSp2Test {

    private static final String OLD_BODY_HASH = "p-298d75417e49";   // executor 1.2.0
    private static final String V130_BODY_HASH = "p-2f656a1aa8cc";  // executor 1.3.0
    private static final String V140_BODY_HASH = "p-b57045e8ccdc";  // executor 1.4.0

    @Test
    void executorPromptIs150WithTheConvictionProfile() {
        PromptDocument doc = PromptDocument.fromClasspath("prompts/executor.md");
        assertThat(doc.version()).isEqualTo("1.5.0");
        String body = doc.body();
        assertThat(body).contains("MECHANISM_BUDGET").contains("withheld on purpose")
                .contains("<!-- rule_version: exec-v1.0 -->")
                .contains("KILL_LEVEL_BREACHED").contains("kill_close_below")
                .contains("kill_close_below_dropped")
                .contains("exit_profile").contains("CONVICTION").contains("PROFILE_MANAGED")
                .contains("SIZE_TOO_SMALL").contains("TECH_CONVICTION")
                .contains("(diversity → freshness)");
    }

    @Test
    void archiveHolds140Verbatim() {
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/executor/1.4.0.md");
        assertThat(PromptHashes.hash(archived)).isEqualTo(V140_BODY_HASH);
        assertThat(new PromptArchive().wasShipped("executor", archived,
                PromptDocument.bodyFromClasspath("prompts/executor.md"))).isTrue();
    }

    @Test
    void archiveHolds130Verbatim() {
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/executor/1.3.0.md");
        assertThat(PromptHashes.hash(archived)).isEqualTo(V130_BODY_HASH);
        assertThat(new PromptArchive().wasShipped("executor", archived,
                PromptDocument.bodyFromClasspath("prompts/executor.md"))).isTrue();
    }

    @Test
    void archiveHolds120Verbatim() {
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/executor/1.2.0.md");
        assertThat(PromptHashes.hash(archived)).isEqualTo(OLD_BODY_HASH);
        assertThat(new PromptArchive().wasShipped("executor", archived,
                PromptDocument.bodyFromClasspath("prompts/executor.md"))).isTrue();
    }
}
