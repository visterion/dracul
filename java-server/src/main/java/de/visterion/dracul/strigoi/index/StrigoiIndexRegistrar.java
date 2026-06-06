package de.visterion.dracul.strigoi.index;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(value = "dracul.strigoi.index.enabled", havingValue = "true")
public class StrigoiIndexRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StrigoiIndexRegistrar.class);
    private static final String AGENT_NAME = "strigoi-index";

    private final VistierieClient vistierie;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String webhookToken;
    private final String schedule;
    private final AppSettingsRepository settings;

    public StrigoiIndexRegistrar(
            VistierieClient vistierie,
            ObjectMapper mapper,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.strigoi.index.webhook-token}") String webhookToken,
            @Value("${dracul.strigoi.index.schedule}") String schedule,
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
                log.info("strigoi-index agent registered with Vistierie");
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
                log.info("strigoi-index agent updated in Vistierie");
            } else {
                log.info("strigoi-index agent registration is up-to-date");
            }
        } catch (Exception e) {
            log.warn("strigoi-index registration failed: {}", e.getMessage());
        }
    }

    @EventListener(LanguageChangedEvent.class)
    public void onLanguageChanged(LanguageChangedEvent event) {
        log.info("language changed to {}; re-registering strigoi-index", event.language());
        register();
    }

    private CreateAgentRequest buildRequest() {
        var prompt = LanguageDirective.append(
                readClasspath("prompts/strigoi-index.md"), settings.getLanguage());
        JsonNode schema;
        try {
            schema = mapper.readTree(readClasspath("schemas/prey-list-index.json"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse output schema", e);
        }
        JsonNode inputSchema;
        try {
            inputSchema = mapper.readTree("""
                    {"type":"object","properties":{"lookback_days":{"type":"integer","minimum":1,"maximum":90}}}
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var tool = new ToolDef(
                "fetch_recent_index_additions",
                "Returns S&P 500 constituents added to the index within the last N days.",
                inputSchema,
                null, null,
                publicUrl + "/api/strigoi-index/tools/fetch-candidates",
                30
        );
        return new CreateAgentRequest(
                AGENT_NAME, prompt, "routine",
                List.of(tool), schema,
                25, 1800,
                webhookToken, schedule,
                publicUrl + "/api/strigoi-index/complete",
                webhookToken
        );
    }

    private boolean matches(AgentDetail existing, CreateAgentRequest desired) {
        return Objects.equals(existing.system_prompt(), desired.system_prompt())
                && Objects.equals(existing.schedule(), desired.schedule())
                && Objects.equals(existing.completion_webhook(), desired.completion_webhook())
                && Objects.equals(existing.completion_webhook_token(), desired.completion_webhook_token())
                && Objects.equals(existing.model_purpose(), desired.model_purpose())
                && Objects.equals(existing.tools(), desired.tools())
                && Objects.equals(existing.output_schema(), desired.output_schema());
    }

    private String readClasspath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read classpath:" + path, e);
        }
    }
}
