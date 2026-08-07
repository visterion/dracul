package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive under {@code prompts/archive/} became load-bearing at runtime (it is the oracle that
 * tells a stale default from an operator edit), so its reachability is now pinned rather than
 * assumed. Before this, {@code archive/README.md} explicitly said "nothing here is read at
 * runtime".
 */
class PromptArchiveTest {

    private final PromptArchive archive = new PromptArchive();

    @Test
    void archiveIsReachableOnTheClasspathAndNotEmpty() {
        assertThat(archive.archivedHashes("gropar"))
                .as("prompts/archive/gropar/*.md must be on the classpath")
                .isNotEmpty();
        assertThat(archive.archivedHashes("strigoi-spin")).isNotEmpty();
    }

    @Test
    void agentOfParsesBothPlainAndNestedJarPaths() {
        assertThat(PromptArchive.agentOf("/build/classes/prompts/archive/gropar/1.2.0.md"))
                .isEqualTo("gropar");
        assertThat(PromptArchive.agentOf(
                "nested:/app/app.jar/!BOOT-INF/classes/!/prompts/archive/strigoi-spin/1.1.0.md"))
                .isEqualTo("strigoi-spin");
        assertThat(PromptArchive.agentOf("1.2.0.md")).isNull();
    }

    @Test
    void archivedVersionCountsAreGroupedPerAgentNotPooled() {
        // Grouping bug guard: a hash archived for one agent must not vouch for another.
        String groparOld = PromptHashes.hash(PromptDocument.bodyFromClasspath(
                "prompts/archive/gropar/1.2.0.md"));
        assertThat(archive.archivedHashes("gropar")).contains(groparOld);
        assertThat(archive.archivedHashes("strigoi-spin")).doesNotContain(groparOld);
    }

    @Test
    void wasShippedAcceptsTheCurrentBundledDefault() {
        String bundled = PromptDocument.bodyFromClasspath("prompts/gropar.md");
        assertThat(archive.wasShipped("gropar", bundled, bundled)).isTrue();
    }

    @Test
    void wasShippedAcceptsAPreviouslyArchivedVersion() {
        String bundled = PromptDocument.bodyFromClasspath("prompts/gropar.md");
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/gropar/1.2.0.md");
        assertThat(archived).isNotEqualTo(bundled);
        assertThat(archive.wasShipped("gropar", archived, bundled)).isTrue();
    }

    @Test
    void wasShippedRejectsTextDraculNeverShipped() {
        String bundled = PromptDocument.bodyFromClasspath("prompts/gropar.md");
        assertThat(archive.wasShipped("gropar", "SYNTHETIC OPERATOR EDIT", bundled)).isFalse();
        assertThat(archive.wasShipped("gropar", null, bundled)).isFalse();
        assertThat(archive.wasShipped("no-such-agent", "anything", bundled)).isFalse();
    }
}
