package de.visterion.dracul.strigoi.lazarus;

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
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
public class StrigoiLazarusRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StrigoiLazarusRegistrar.class);
    private static final String AGENT_NAME = "strigoi-lazarus";

    private final VistierieClient vistierie;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String webhookToken;
    private final String schedule;

    public StrigoiLazarusRegistrar(
            VistierieClient vistierie,
            ObjectMapper mapper,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.strigoi.lazarus.webhook-token}") String webhookToken,
            @Value("${dracul.strigoi.lazarus.schedule}") String schedule) {
        this.vistierie = vistierie;
        this.mapper = mapper;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.webhookToken = webhookToken;
        this.schedule = schedule;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            var desired = buildRequest();
            var existing = vistierie.getAgent(AGENT_NAME);
            if (existing.isEmpty()) {
                vistierie.registerAgent(desired);
                log.info("strigoi-lazarus agent registered with Vistierie");
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
                log.info("strigoi-lazarus agent updated in Vistierie");
            } else {
                log.info("strigoi-lazarus agent registration is up-to-date");
            }
        } catch (Exception e) {
            log.warn("strigoi-lazarus registration failed: {}", e.getMessage());
        }
    }

    private CreateAgentRequest buildRequest() {
        var prompt = readClasspath("prompts/strigoi-lazarus.md");
        JsonNode schema;
        try {
            schema = mapper.readTree(readClasspath("schemas/prey-list-lazarus.json"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse output schema", e);
        }
        JsonNode inputSchema;
        try {
            inputSchema = mapper.readTree("""
                    {"type":"object","properties":{}}
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var tool = new ToolDef(
                "fetch_quality_at_low_candidates",
                "Returns watchlist names currently trading near their 52-week low, with fundamentals.",
                inputSchema,
                null, null,
                publicUrl + "/api/strigoi-lazarus/tools/fetch-candidates",
                30
        );
        return new CreateAgentRequest(
                AGENT_NAME, prompt, "reasoning",
                List.of(tool), schema,
                25, 1800,
                webhookToken, schedule,
                publicUrl + "/api/strigoi-lazarus/complete",
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
