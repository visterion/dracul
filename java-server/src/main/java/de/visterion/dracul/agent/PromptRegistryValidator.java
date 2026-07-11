package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bootstrap guard: cross-checks every enabled agent's classpath prompt file and
 * DB-stored prompt against {@link PromptRegistry}, so a prompt edit that forgot to
 * bump the registry (or a stale DB row) surfaces as a warning instead of silently
 * drifting. Hard mismatches (registry vs. bundled file) set a health flag in
 * {@link AppSettingsRepository}; DB-vs-registry divergence alone is informational
 * only, since a user-edited prompt is legitimate.
 */
@Component
@Order(10)
public class PromptRegistryValidator {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistryValidator.class);
    private static final String HEALTH_KEY = "health.prompt_registry";
    private static final String OK = "OK";

    private final AgentDefinitionStore store;
    private final PromptRegistry registry;
    private final AppSettingsRepository settings;

    public PromptRegistryValidator(AgentDefinitionStore store, PromptRegistry registry,
                                   AppSettingsRepository settings) {
        this.store = store;
        this.registry = registry;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> mismatched = new ArrayList<>();
        for (var def : store.findAllEnabled()) {
            if (!checkAgent(def)) {
                mismatched.add(def.name());
            }
        }
        settings.put(HEALTH_KEY, mismatched.isEmpty() ? OK : "MISMATCH:" + String.join(",", mismatched));
    }

    /** Returns false on a hard mismatch (registry vs. bundled prompt file). */
    private boolean checkAgent(AgentDefinition def) {
        String agent = def.name();
        var entryOpt = registry.entry(agent);
        if (entryOpt.isEmpty()) {
            log.warn("prompt registry: no entry for enabled agent '{}'", agent);
            return false;
        }
        var entry = entryOpt.get();

        PromptDocument doc;
        try {
            doc = PromptDocument.fromClasspath("prompts/" + agent + ".md");
        } catch (Exception e) {
            log.warn("prompt registry: no classpath prompt file for enabled agent '{}'", agent);
            return false;
        }

        boolean ok = true;

        if (!Objects.equals(doc.version(), entry.version())) {
            log.warn("prompt registry: '{}' file header version '{}' != registry version '{}'",
                    agent, doc.version(), entry.version());
            ok = false;
        }

        String fileHash = PromptHashes.hash(doc.body());
        if (!Objects.equals(fileHash, entry.bodyHash())) {
            log.warn("prompt registry: '{}' prompt file changed without registry bump "
                    + "(file hash '{}' != registry hash '{}')", agent, fileHash, entry.bodyHash());
            ok = false;
        }

        String dbHash = PromptHashes.hash(def.promptText());
        if (!Objects.equals(dbHash, entry.bodyHash())) {
            log.info("prompt registry: '{}' DB prompt diverges from bundled default "
                    + "(db hash '{}' != registry hash '{}') — user edit or stale default",
                    agent, dbHash, entry.bodyHash());
        }

        return ok;
    }
}
