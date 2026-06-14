package de.visterion.dracul.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Seeds code defaults into the store (upsert-if-absent) so user edits survive
 *  redeploys. Runs before GenericAgentRegistrar (lower @Order). */
@Component
@Order(10)
public class AgentDefinitionBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionBootstrap.class);

    private final List<AgentDefaultProvider> providers;
    private final AgentDefinitionStore store;

    public AgentDefinitionBootstrap(List<AgentDefaultProvider> providers,
                                    AgentDefinitionStore store) {
        this.providers = providers;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        seed();
    }

    public void seed() {
        int inserted = 0;
        for (var p : providers) {
            if (store.insertIfAbsent(p.defaultDefinition())) inserted++;
        }
        log.info("agent definition bootstrap: {} provider(s), {} newly seeded",
                providers.size(), inserted);
    }
}
