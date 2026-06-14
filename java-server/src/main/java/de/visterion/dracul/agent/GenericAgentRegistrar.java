package de.visterion.dracul.agent;

import de.visterion.dracul.i18n.LanguageChangedEvent;
import de.visterion.dracul.i18n.LanguageDirective;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
@Order(20)
public class GenericAgentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentRegistrar.class);

    private final VistierieClient vistierie;
    private final AgentDefinitionStore store;
    private final AgentToolCatalog catalog;
    private final AppSettingsRepository settings;
    private final String publicUrl;
    private final TokenResolver tokenResolver;

    public GenericAgentRegistrar(VistierieClient vistierie, AgentDefinitionStore store,
                                 AgentToolCatalog catalog, AppSettingsRepository settings,
                                 @Value("${dracul.public-url}") String publicUrl,
                                 TokenResolver tokenResolver) {
        this.vistierie = vistierie;
        this.store = store;
        this.catalog = catalog;
        this.settings = settings;
        this.publicUrl = publicUrl.endsWith("/")
                ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.tokenResolver = tokenResolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        for (var def : store.findAllEnabled()) {
            try { registerOne(def); }
            catch (Exception e) { log.warn("{} registration failed: {}", def.name(), e.getMessage()); }
        }
    }

    @EventListener(AgentDefinitionChangedEvent.class)
    public void onChanged(AgentDefinitionChangedEvent e) {
        store.find(e.name()).ifPresent(def -> {
            try { registerOne(def); }
            catch (Exception ex) { log.warn("{} re-register failed: {}", def.name(), ex.getMessage()); }
        });
    }

    @EventListener(LanguageChangedEvent.class)
    public void onLanguageChanged(LanguageChangedEvent e) {
        log.info("language changed to {}; re-registering all agents", e.language());
        registerAll();
    }

    private void registerOne(AgentDefinition def) {
        if (!def.enabled()) {
            log.info("{} is disabled — skipping registration", def.name());
            return;
        }
        var desired = buildRequest(def);
        var existing = vistierie.getAgent(def.name());
        if (existing.isEmpty()) {
            vistierie.registerAgent(desired);
            log.info("{} registered with Vistierie", def.name());
        } else if (!matches(def, existing.get(), desired)) {
            vistierie.updateAgent(def.name(), toUpdateRequest(desired, def.isStreaming()));
            log.info("{} updated in Vistierie", def.name());
        } else {
            log.info("{} registration is up-to-date", def.name());
        }
    }

    private UpdateAgentRequest toUpdateRequest(CreateAgentRequest desired, boolean streaming) {
        if (streaming) {
            return new UpdateAgentRequest(
                    desired.system_prompt(), desired.model_purpose(), desired.tools(),
                    desired.output_schema(), desired.max_turns(), desired.max_run_seconds(),
                    desired.webhook_token(), desired.schedule(), desired.completion_webhook(),
                    desired.completion_webhook_token(), desired.event_source_url(),
                    desired.session_duration_seconds(), desired.poll_interval_seconds());
        }
        return new UpdateAgentRequest(
                desired.system_prompt(), desired.model_purpose(), desired.tools(),
                desired.output_schema(), desired.max_turns(), desired.max_run_seconds(),
                desired.webhook_token(), desired.schedule(), desired.completion_webhook(),
                desired.completion_webhook_token());
    }

    // package-private for test access
    CreateAgentRequest buildRequest(AgentDefinition def) {
        String token = tokenResolver.resolve(def.name());
        String prompt = LanguageDirective.append(def.promptText(), settings.getLanguage());
        var tools = def.tools().stream().map(b -> {
            var entry = catalog.find(b.toolName())
                    .orElseThrow(() -> new IllegalStateException("unknown tool " + b.toolName()));
            String description = b.description() != null ? b.description() : entry.defaultDescription();
            return new ToolDef(entry.toolName(), description, entry.inputSchema(),
                    null, null, publicUrl + entry.callbackPath(), entry.timeoutSeconds());
        }).toList();
        String completion = publicUrl + def.completionPath();
        if (def.isStreaming()) {
            return new CreateAgentRequest(def.name(), prompt, def.modelPurpose(), tools,
                    def.outputSchema(), def.maxTurns(), def.maxRunSeconds(), token,
                    def.schedule(), completion, token,
                    publicUrl + def.eventSourcePath(), def.sessionDurationSeconds(),
                    def.pollIntervalSeconds());
        }
        return new CreateAgentRequest(def.name(), prompt, def.modelPurpose(), tools,
                def.outputSchema(), def.maxTurns(), def.maxRunSeconds(), token,
                def.schedule(), completion, token);
    }

    /**
     * Checks whether the existing Vistierie agent matches the desired state.
     *
     * <p>Streaming agents deliberately omit tool comparison (mirroring the former DaywalkerRegistrar):
     * {@link AgentDetail} carries no streaming fields, so changes to streaming-only fields
     * cannot be detected here and require a manual re-register. v1 accepts this limitation.
     */
    private boolean matches(AgentDefinition def, AgentDetail existing, CreateAgentRequest desired) {
        boolean base = Objects.equals(existing.system_prompt(), desired.system_prompt())
                && Objects.equals(existing.schedule(), desired.schedule())
                && Objects.equals(existing.completion_webhook(), desired.completion_webhook())
                && Objects.equals(existing.completion_webhook_token(), desired.completion_webhook_token())
                && Objects.equals(existing.model_purpose(), desired.model_purpose())
                && Objects.equals(existing.output_schema(), desired.output_schema());
        if (def.isStreaming()) {
            return base;
        }
        return base && Objects.equals(existing.tools(), desired.tools());
    }
}
