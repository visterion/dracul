package de.visterion.dracul.gropar;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.agent.AgentDefaultProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the full context with gropar ENABLED to prove its gated beans wire and it
 *  registers through the generic AgentDefaultProvider pipeline. */
@SpringBootTest(properties = {
    "dracul.gropar.enabled=true",
    "dracul.gropar.webhook-token=test-token"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class GroparWiringTest {

    @Autowired GroparWebhookController controller;
    @Autowired ExitIndicatorService indicatorService;
    @Autowired List<AgentDefaultProvider> providers;

    @Test
    void groparBeansWireAndProviderRegistered() {
        assertThat(controller).isNotNull();
        assertThat(indicatorService).isNotNull();
        assertThat(providers).anySatisfy(p ->
                assertThat(p.defaultDefinition().name()).isEqualTo("gropar"));
    }
}
