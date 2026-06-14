package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
    "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
    "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
    "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentDefinitionBootstrapTest {

    @Autowired AgentDefinitionBootstrap bootstrap;
    @Autowired AgentDefinitionStore store;

    @Test
    void seedsAllProvidersAndPreservesEdits() {
        bootstrap.seed();   // idempotent
        assertThat(store.find("strigoi-echo")).isPresent();

        // Edit, then re-seed: the edit must survive (upsert-if-absent).
        var echo = store.find("strigoi-echo").orElseThrow();
        store.save(new AgentDefinition(echo.name(), echo.modelPurpose(), "EDITED PROMPT",
                echo.outputSchema(), echo.schedule(), echo.maxTurns(), echo.maxRunSeconds(),
                echo.completionPath(), null, null, null, echo.enabled(), echo.tools()));

        bootstrap.seed();

        assertThat(store.find("strigoi-echo").orElseThrow().promptText())
                .isEqualTo("EDITED PROMPT");
    }
}
