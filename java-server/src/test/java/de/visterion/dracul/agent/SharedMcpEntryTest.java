package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves the "search" mcp tool is contributed to {@link AgentToolCatalog} exactly once,
 * unconditionally, via {@link ToolCatalogContributor} — independent of which (if any) of the
 * 8 mcp-consuming {@code *Defaults} agents happen to be enabled. See spec §5.4: contributing
 * "search" from inside one of those 8 conditional beans would create a partial-enable hazard
 * (the catalog entry would only exist when that one agent happened to be on).
 */
class SharedMcpEntryTest {

    @Nested
    @SpringBootTest(properties = {
        "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
        "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true",
        "dracul.renfield.enabled=true",
        "dracul.voievod-outcome.enabled=true",
        "dracul.voievod-outcome.webhook-token=test-token",
        "dracul.voievod-outcome.schedule=0 0 7 * * 6"
    })
    @Import(ContainerConfig.class)
    @ActiveProfiles("dev")
    class AllAgentsEnabled {

        @MockitoBean VistierieClient vistierie;
        @Autowired AgentToolCatalog catalog;
        @Autowired AgentDefinitionBootstrap bootstrap;
        @Autowired AgentDefinitionStore store;

        @Test
        void bootsWithoutDuplicateToolException() {
            // Context already started successfully (constructor didn't throw) — assert the
            // catalog carries the contributed "search" entry with mcp-shaped metadata.
            var entry = catalog.find("search");
            assertThat(entry).isPresent();
            assertThat(entry.get().callbackPath()).isNull();
            assertThat(entry.get().inputSchema()).isNotNull();
        }

        @Test
        void seedNeverInsertsPhantomSearchAgentDefinition() {
            assertThatCode(bootstrap::seed).doesNotThrowAnyException();
            var names = store.findAllEnabled().stream().map(AgentDefinition::name).toList();
            assertThat(names).doesNotContain("search");
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "dracul.daywalker.enabled=false", "dracul.voievod.enabled=false",
        "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=false",
        "dracul.strigoi.merger.enabled=false", "dracul.strigoi.index.enabled=false",
        "dracul.strigoi.insider.enabled=false", "dracul.strigoi.spin.enabled=false",
        "dracul.renfield.enabled=false",
        "dracul.voievod-outcome.enabled=false"
    })
    @Import(ContainerConfig.class)
    @ActiveProfiles("dev")
    class OnlyOneAgentEnabled {

        @MockitoBean VistierieClient vistierie;
        @Autowired AgentDefinitionBootstrap bootstrap;
        @Autowired AgentDefinitionStore store;
        @Autowired GenericAgentRegistrar registrar;
        @Autowired AgentToolCatalog catalog;

        @Test
        void catalogStillCarriesSearchAndTheOneAgentBuildsFine() {
            assertThat(catalog.find("search")).isPresent();

            bootstrap.seed();
            var def = store.findAllEnabled().stream()
                    .filter(d -> d.name().contains("echo"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected the echo agent to be enabled"));

            assertThatCode(() -> registrar.buildRequest(def)).doesNotThrowAnyException();
        }
    }
}
