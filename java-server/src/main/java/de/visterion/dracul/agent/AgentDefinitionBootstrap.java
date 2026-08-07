package de.visterion.dracul.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Seeds code defaults into the store and reconciles stale bundled prompts.
 *
 * <p>Seeding is insert-if-absent so operator edits survive a redeploy. That alone, however, made
 * the store a permanent cache of whatever prompt happened to be bundled the day the row was first
 * written: {@link GenericAgentRegistrar} builds its Vistierie request from the <em>store</em>, so a
 * prompt change in the repo could never reach the agent. It also could not be spotted, because the
 * registrar correctly reported "up-to-date" (store and Vistierie really did agree) while only an
 * INFO line mentioned the drift.
 *
 * <p>So this class now also reconciles: when the stored prompt differs from the bundled default, it
 * asks {@link PromptArchive} whether the stored text is one Dracul once shipped.
 * <ul>
 *   <li><b>Yes</b> — nobody edited it, it is merely stale. Overwrite it with the bundled default
 *       (prompt body only; every other field the operator may have tuned is left alone).</li>
 *   <li><b>No</b> — it is an operator edit. Keep it, and WARN that the bundled prompt cannot reach
 *       this agent, naming the reset procedure.</li>
 * </ul>
 * The repo is the source of truth for everything the operator has not deliberately changed; a
 * deliberate change is never destroyed silently, only reported loudly.
 */
@Component
public class AgentDefinitionBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionBootstrap.class);

    private final List<AgentDefaultProvider> providers;
    private final AgentDefinitionStore store;
    private final PromptArchive archive;

    public AgentDefinitionBootstrap(List<AgentDefaultProvider> providers,
                                    AgentDefinitionStore store,
                                    PromptArchive archive) {
        this.providers = providers;
        this.store = store;
        this.archive = archive;
    }

    /**
     * NOTE: the {@code @Order} belongs on the listener METHOD, not on the class —
     * {@code ApplicationListenerMethodAdapter.resolveOrder} reads {@code @Order} from the method
     * only and falls back to {@code LOWEST_PRECEDENCE}, so a class-level {@code @Order} leaves the
     * startup listeners in undefined order. This must run before {@link PromptRegistryValidator}
     * (15) and {@link GenericAgentRegistrar} (20), which both read the store this method writes.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onReady() {
        seed();
    }

    public void seed() {
        int inserted = 0;
        int reconciled = 0;
        int blocked = 0;
        for (var p : providers) {
            var desired = p.defaultDefinition();
            if (desired == null) {
                continue;
            }
            if (store.insertIfAbsent(desired)) {
                inserted++;
                continue;
            }
            var existing = store.find(desired.name()).orElse(null);
            if (existing == null || Objects.equals(existing.promptText(), desired.promptText())) {
                continue;
            }
            String storedHash = PromptHashes.hash(existing.promptText());
            String bundledHash = PromptHashes.hash(desired.promptText());
            if (archive.wasShipped(desired.name(), existing.promptText(), desired.promptText())) {
                store.updatePromptText(desired.name(), desired.promptText());
                reconciled++;
                log.info("{} stored prompt was a previously shipped default ({}) — reconciled to the "
                                + "bundled default ({})",
                        desired.name(), storedHash, bundledHash);
            } else {
                blocked++;
                log.warn("{} stored prompt diverges from bundled default and matches no version "
                                + "Dracul ever shipped (stored {}, bundled {}) — keeping it as an "
                                + "operator edit. THE BUNDLED PROMPT CANNOT REACH THIS AGENT until the "
                                + "stored prompt is reset (agent-definition reset, see the operations "
                                + "runbook).",
                        desired.name(), storedHash, bundledHash);
            }
        }
        log.info("agent definition bootstrap: {} provider(s), {} newly seeded, {} prompt(s) "
                        + "reconciled to the bundled default, {} blocked by an operator edit",
                providers.size(), inserted, reconciled, blocked);
    }
}
