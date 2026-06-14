package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "dracul.daywalker.enabled=true",
        "dracul.voievod.enabled=true",
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.merger.enabled=true",
        "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.insider.enabled=true",
        "dracul.strigoi.spin.enabled=true"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentDefaultProviderWiringTest {

    @Autowired List<AgentDefaultProvider> providers;
    @Autowired AgentToolCatalog catalog;

    @Test
    void allEightAgentsHaveProviders() {
        Set<String> names = providers.stream()
                .map(p -> p.defaultDefinition().name()).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "daywalker", "voievod", "strigoi-echo", "strigoi-lazarus",
                "strigoi-merger", "strigoi-index", "strigoi-insider", "strigoi-spin");
    }

    @Test
    void everyToolBindingResolvesInCatalog() {
        for (var p : providers) {
            for (var t : p.defaultDefinition().tools()) {
                assertThat(catalog.contains(t.toolName()))
                        .as("tool %s of agent %s", t.toolName(), p.defaultDefinition().name())
                        .isTrue();
            }
        }
    }

    @Test
    void daywalkerIsStreamingWithNoTools() {
        var dw = providers.stream().map(AgentDefaultProvider::defaultDefinition)
                .filter(d -> d.name().equals("daywalker")).findFirst().orElseThrow();
        assertThat(dw.isStreaming()).isTrue();
        assertThat(dw.tools()).isEmpty();
    }
}
