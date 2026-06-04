package de.visterion.dracul.strigoi.spin;

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
@ConditionalOnProperty(value = "dracul.strigoi.spin.enabled", havingValue = "true")
public class StrigoiSpinRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StrigoiSpinRegistrar.class);
    private static final String AGENT_NAME = "strigoi-spin";

    private final VistierieClient vistierie;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String webhookToken;
    private final String schedule;

    public StrigoiSpinRegistrar(
            VistierieClient vistierie,
            ObjectMapper mapper,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.strigoi.spin.webhook-token}") String webhookToken,
            @Value("${dracul.strigoi.spin.schedule}") String schedule) {
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
                log.info("strigoi-spin agent registered with Vistierie");
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
                log.info("strigoi-spin agent updated in Vistierie");
            } else {
                log.info("strigoi-spin agent registration is up-to-date");
            }
        } catch (Exception e) {
            log.warn("strigoi-spin registration failed: {}", e.getMessage());
        }
    }

    private CreateAgentRequest buildRequest() {
        var prompt = readClasspath("prompts/strigoi-spin.md");
        JsonNode schema;
        try {
            schema = mapper.readTree(readClasspath("schemas/prey-list-spin.json"));
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
                "fetch_recent_spinoff_candidates",
                "Returns recent SEC Form 10-12B spin-off registrations from the last N days.",
                inputSchema,
                null, null,
                publicUrl + "/api/strigoi-spin/tools/fetch-candidates",
                30
        );
        return new CreateAgentRequest(
                AGENT_NAME, prompt, "reasoning",
                List.of(tool), schema,
                25, 1800,
                webhookToken, schedule,
                publicUrl + "/api/strigoi-spin/complete",
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
