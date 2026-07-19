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
import tools.jackson.databind.ObjectMapper;
import java.util.List;
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
    private final List<AgentDefaultProvider> providers;
    private final ObjectMapper mapper;
    private final String hiveMemBaseUrl;
    private final String hiveMemAgentReadToken;

    public GenericAgentRegistrar(VistierieClient vistierie, AgentDefinitionStore store,
                                 AgentToolCatalog catalog, AppSettingsRepository settings,
                                 @Value("${dracul.public-url}") String publicUrl,
                                 TokenResolver tokenResolver,
                                 List<AgentDefaultProvider> providers,
                                 ObjectMapper mapper,
                                 @Value("${dracul.hivemem.base-url:http://hivemem:8421}") String hiveMemBaseUrl,
                                 @Value("${dracul.hivemem.agent-read-token:}") String hiveMemAgentReadToken) {
        this.vistierie = vistierie;
        this.store = store;
        this.catalog = catalog;
        this.settings = settings;
        this.publicUrl = publicUrl.endsWith("/")
                ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.tokenResolver = tokenResolver;
        this.providers = providers;
        this.mapper = mapper;
        this.hiveMemBaseUrl = hiveMemBaseUrl;
        this.hiveMemAgentReadToken = hiveMemAgentReadToken;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        checkMcpTokenConfigured();
        for (var def : store.findAllEnabled()) {
            try { registerOne(def); }
            catch (Exception e) { log.warn("{} registration failed: {}", def.name(), e.getMessage()); }
        }
    }

    /**
     * Fail-fast (not per-agent WARN): reads the enabled {@code *Defaults} CODE-DEFAULT beans, not
     * the store — the store is empty at {@code @PostConstruct}/config time on a fresh DB or right
     * after a definition-reset boot (the bootstrap only seeds at {@link ApplicationReadyEvent}), so
     * checking the store would silently skip this guard exactly when it matters most. See spec
     * §5.3.
     */
    void checkMcpTokenConfigured() {
        boolean anyMcpBinding = providers.stream()
                .map(AgentDefaultProvider::defaultDefinition)
                .filter(Objects::nonNull)
                .filter(AgentDefinition::enabled)
                .flatMap(d -> d.tools().stream())
                .anyMatch(t -> "search".equals(t.toolName()));
        if (anyMcpBinding && (hiveMemAgentReadToken == null || hiveMemAgentReadToken.isBlank())) {
            throw new IllegalStateException(
                    "dracul.hivemem.agent-read-token (DRACUL_HIVEMEM_AGENT_READ_TOKEN) is blank "
                            + "but at least one enabled agent has an mcp search binding — refusing to "
                            + "start (would register all mcp-tooled agents with an empty credential)");
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
                    desired.session_duration_seconds(), desired.poll_interval_seconds(),
                    desired.mcp_credentials());
        }
        return new UpdateAgentRequest(
                desired.system_prompt(), desired.model_purpose(), desired.tools(),
                desired.output_schema(), desired.max_turns(), desired.max_run_seconds(),
                desired.webhook_token(), desired.schedule(), desired.completion_webhook(),
                desired.completion_webhook_token(), null, null, null,
                desired.mcp_credentials());
    }

    // package-private for test access
    CreateAgentRequest buildRequest(AgentDefinition def) {
        String token = tokenResolver.resolve(def.name());
        String prompt = LanguageDirective.append(def.promptText(), settings.getLanguage());
        var tools = def.tools().stream().map(b -> {
            var entry = catalog.find(b.toolName())
                    .orElseThrow(() -> new IllegalStateException("unknown tool " + b.toolName()));
            String description = b.description() != null ? b.description() : entry.defaultDescription();
            if ("search".equals(entry.toolName())) {
                return new ToolDef(entry.toolName(), description, entry.inputSchema(),
                        "mcp", null, null, null, hiveMemBaseUrl, "search", null);
            }
            return new ToolDef(entry.toolName(), description, entry.inputSchema(),
                    null, null, publicUrl + entry.callbackPath(), entry.timeoutSeconds());
        }).toList();
        String completion = publicUrl + def.completionPath();
        var mcpCredentials = tools.stream().anyMatch(t -> "mcp".equals(t.type()))
                ? mapper.createObjectNode().put(hiveMemBaseUrl, hiveMemAgentReadToken)
                : null;
        if (def.isStreaming()) {
            return new CreateAgentRequest(def.name(), prompt, def.modelPurpose(), tools,
                    def.outputSchema(), def.maxTurns(), def.maxRunSeconds(), token,
                    def.schedule(), completion, token,
                    publicUrl + def.eventSourcePath(), def.sessionDurationSeconds(),
                    def.pollIntervalSeconds(), mcpCredentials);
        }
        return new CreateAgentRequest(def.name(), prompt, def.modelPurpose(), tools,
                def.outputSchema(), def.maxTurns(), def.maxRunSeconds(), token,
                def.schedule(), completion, token, null, null, null, mcpCredentials);
    }

    /**
     * Checks whether the existing Vistierie agent matches the desired state.
     *
     * <p>Streaming agents deliberately omit tool comparison (mirroring the former DaywalkerRegistrar),
     * but compare their three streaming-only fields ({@code event_source_url},
     * {@code session_duration_seconds}, {@code poll_interval_seconds}) so drift there also
     * triggers a re-register.
     */
    private boolean matches(AgentDefinition def, AgentDetail existing, CreateAgentRequest desired) {
        boolean base = Objects.equals(existing.system_prompt(), desired.system_prompt())
                && Objects.equals(existing.schedule(), desired.schedule())
                && Objects.equals(existing.completion_webhook(), desired.completion_webhook())
                && Objects.equals(existing.completion_webhook_token(), desired.completion_webhook_token())
                && Objects.equals(existing.model_purpose(), desired.model_purpose())
                && Objects.equals(existing.output_schema(), desired.output_schema())
                && Objects.equals(existing.max_turns(), desired.max_turns())
                && Objects.equals(existing.max_run_seconds(), desired.max_run_seconds());
        if (def.isStreaming()) {
            return base
                    && Objects.equals(existing.event_source_url(), desired.event_source_url())
                    && Objects.equals(existing.session_duration_seconds(), desired.session_duration_seconds())
                    && Objects.equals(existing.poll_interval_seconds(), desired.poll_interval_seconds());
        }
        return base && Objects.equals(existing.tools(), desired.tools());
    }
}
