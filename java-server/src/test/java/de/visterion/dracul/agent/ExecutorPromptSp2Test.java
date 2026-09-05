package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorPromptSp2Test {

    private static final String OLD_BODY_HASH = "p-298d75417e49";   // executor 1.2.0

    @Test
    void executorPromptIs130WithSp2Text() {
        PromptDocument doc = PromptDocument.fromClasspath("prompts/executor.md");
        assertThat(doc.version()).isEqualTo("1.3.0");
        String body = doc.body();
        assertThat(body).contains("MECHANISM_BUDGET").contains("withheld on purpose")
                .contains("<!-- rule_version: exec-v0.6 -->")
                .contains("(diversity → freshness)")
                .doesNotContain("(diversity → confidence → freshness)");
    }

    @Test
    void archiveHolds120Verbatim() {
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/executor/1.2.0.md");
        assertThat(PromptHashes.hash(archived)).isEqualTo(OLD_BODY_HASH);
        assertThat(new PromptArchive().wasShipped("executor", archived,
                PromptDocument.bodyFromClasspath("prompts/executor.md"))).isTrue();
    }
}
