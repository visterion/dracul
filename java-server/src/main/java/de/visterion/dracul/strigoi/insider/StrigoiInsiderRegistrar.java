package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.i18n.LanguageChangedEvent;
import de.visterion.dracul.i18n.LanguageDirective;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.vistierie.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(value = "dracul.strigoi.insider.enabled", havingValue = "true")
public class StrigoiInsiderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StrigoiInsiderRegistrar.class);
    private static final String AGENT_NAME = "strigoi-insider";

    private final VistierieClient vistierie;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String webhookToken;
    private final String schedule;
    private final AppSettingsRepository settings;

    public StrigoiInsiderRegistrar(
            VistierieClient vistierie,
            ObjectMapper mapper,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.strigoi.insider.webhook-token}") String webhookToken,
            @Value("${dracul.strigoi.insider.schedule}") String schedule,
            AppSettingsRepository settings) {
        this.vistierie = vistierie;
        this.mapper = mapper;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.webhookToken = webhookToken;
        this.schedule = schedule;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            var desired = buildRequest();
            var existing = vistierie.getAgent(AGENT_NAME);
            if (existing.isEmpty()) {
                vistierie.registerAgent(desired);
                log.info("strigoi-insider agent registered with Vistierie");
                return;
            }
            if (!matches(existing.get(), desired)) {
                var update = new UpdateAgentRequest(
                        desired.system_prompt(), desired.model_purpose(),
                        desired.tools(), desired.output_schema(),
                        desired.max_turns(), desired.max_run_seconds(),
                        desired.webhook_token(), desired.schedule(),
                        desired.completion_webhook(), desired.completion_webhook_token());
                vistierie.updateAgent(AGENT_NAME, update);
                log.info("strigoi-insider agent updated in Vistierie");
            } else {
                log.info("strigoi-insider agent registration is up-to-date");
            }
        } catch (Exception e) {
            log.warn("strigoi-insider registration failed: {}", e.getMessage());
        }
    }

    @EventListener(LanguageChangedEvent.class)
    public void onLanguageChanged(LanguageChangedEvent event) {
        log.info("language changed to {}; re-registering strigoi-insider", event.language());
        register();
    }

    private CreateAgentRequest buildRequest() {
        var prompt = LanguageDirective.append(
                readClasspath("prompts/strigoi-insider.md"), settings.getLanguage());
        JsonNode schema;
        try {
            schema = mapper.readTree(readClasspath("schemas/prey-list.json"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse output schema", e);
        }
        JsonNode inputSchema;
        try {
            inputSchema = mapper.readTree("""
                    {"type":"object","properties":{"lookback_days":{"type":"integer","minimum":1,"maximum":30}}}
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var tool = new ToolDef(
                "fetch_recent_clusters",
                "Returns insider buying clusters detected in the last N days.",
                inputSchema,
                null, null,
                publicUrl + "/api/strigoi-insider/tools/fetch-clusters",
                30
        );
        return new CreateAgentRequest(
                AGENT_NAME, prompt, "routine",
                List.of(tool), schema,
                25, 1800,
                webhookToken, schedule,
                publicUrl + "/api/strigoi-insider/complete",
                webhookToken
        );
    }

    private boolean matches(AgentDetail existing, CreateAgentRequest desired) {
        return java.util.Objects.equals(existing.system_prompt(), desired.system_prompt())
                && java.util.Objects.equals(existing.schedule(), desired.schedule())
                && java.util.Objects.equals(existing.completion_webhook(), desired.completion_webhook())
                && java.util.Objects.equals(existing.completion_webhook_token(), desired.completion_webhook_token())
                && java.util.Objects.equals(existing.model_purpose(), desired.model_purpose())
                && java.util.Objects.equals(existing.tools(), desired.tools())
                && java.util.Objects.equals(existing.output_schema(), desired.output_schema());
    }

    private String readClasspath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read classpath:" + path, e);
        }
    }
}
