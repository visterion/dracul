package de.visterion.dracul.daywalker;

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
@ConditionalOnProperty(value = "dracul.daywalker.enabled", havingValue = "true")
public class DaywalkerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerRegistrar.class);
    private static final String AGENT_NAME = "daywalker";

    private final VistierieClient vistierie;
    private final ObjectMapper mapper;
    private final String publicUrl;
    private final String webhookToken;
    private final String schedule;
    private final int sessionDuration;
    private final int pollInterval;

    public DaywalkerRegistrar(
            VistierieClient vistierie,
            ObjectMapper mapper,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.daywalker.webhook-token}") String webhookToken,
            @Value("${dracul.daywalker.session-cron}") String schedule,
            @Value("${dracul.daywalker.session-duration:23400}") int sessionDuration,
            @Value("${dracul.daywalker.poll-interval:300}") int pollInterval) {
        this.vistierie = vistierie;
        this.mapper = mapper;
        this.publicUrl = publicUrl.endsWith("/")
                ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.webhookToken = webhookToken;
        this.schedule = schedule;
        this.sessionDuration = sessionDuration;
        this.pollInterval = pollInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            var desired = buildRequest();
            var existing = vistierie.getAgent(AGENT_NAME);
            if (existing.isEmpty()) {
                vistierie.registerAgent(desired);
                log.info("daywalker agent registered with Vistierie");
                return;
            }
            if (!matches(existing.get(), desired)) {
                var update = new UpdateAgentRequest(
                        desired.system_prompt(), desired.model_purpose(),
                        desired.tools(), desired.output_schema(),
                        desired.max_turns(), desired.max_run_seconds(),
                        desired.webhook_token(), desired.schedule(),
                        desired.completion_webhook(), desired.completion_webhook_token(),
                        desired.event_source_url(), desired.session_duration_seconds(),
                        desired.poll_interval_seconds());
                vistierie.updateAgent(AGENT_NAME, update);
                log.info("daywalker agent updated in Vistierie");
            } else {
                log.info("daywalker agent registration is up-to-date");
            }
        } catch (Exception e) {
            log.warn("daywalker registration failed: {}", e.getMessage());
        }
    }

    CreateAgentRequest buildRequest() {
        var prompt = readClasspath("prompts/daywalker.md");
        JsonNode schema;
        try {
            schema = mapper.readTree(readClasspath("schemas/daywalker-assessment.json"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse daywalker output schema", e);
        }
        return new CreateAgentRequest(
                AGENT_NAME, prompt, "reasoning",
                List.of(), schema,
                8, 600,
                webhookToken, schedule,
                publicUrl + "/api/daywalker/complete", webhookToken,
                publicUrl + "/api/daywalker/events", sessionDuration, pollInterval);
    }

    private boolean matches(AgentDetail existing, CreateAgentRequest desired) {
        return Objects.equals(existing.system_prompt(), desired.system_prompt())
                && Objects.equals(existing.schedule(), desired.schedule())
                && Objects.equals(existing.completion_webhook(), desired.completion_webhook())
                && Objects.equals(existing.completion_webhook_token(), desired.completion_webhook_token())
                && Objects.equals(existing.model_purpose(), desired.model_purpose())
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
