package de.visterion.dracul.agent;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/** Shared classpath/JSON helpers for the per-agent default providers. */
public final class AgentResources {
    private AgentResources() {}

    public static String classpath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to read classpath: " + path, e);
        }
    }

    public static JsonNode readSchema(ObjectMapper mapper, String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (Exception e) {
            throw new RuntimeException("failed to read schema: " + path, e);
        }
    }

    public static JsonNode parseJson(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse json: " + json, e);
        }
    }
}
