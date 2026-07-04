package de.visterion.dracul.marketdata;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Generic, portable MCP client for Agora's Streamable-HTTP front-door. Holds one long-lived
 * SyncClient; calls are synchronized (the MCP sync client is not built for concurrent use, and
 * Dracul's data-call volume is low — batch quotes are a single call). Nothing here is
 * Dracul-specific: the same pattern consumes Agora from any harness.
 */
@Component
public class AgoraClient {

    private static final Logger log = LoggerFactory.getLogger(AgoraClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String baseUrl;
    private final String token;
    private volatile McpSyncClient client;

    public AgoraClient(@Value("${dracul.agora.base-url:http://agora:8080}") String baseUrl,
                       @Value("${dracul.agora.token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /** Call an Agora tool by name with JSON args; returns the tool's output JSON. Never returns null. */
    public synchronized JsonNode callTool(String name, JsonNode args) {
        Map<String, Object> argsMap = args == null ? Map.of() : MAPPER.convertValue(args, MAP_TYPE);
        try {
            return invoke(name, argsMap);
        } catch (AgoraUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // session may be stale — drop the client, reconnect once, retry
            log.warn("Agora call {} failed ({}); reconnecting", name, e.toString());
            closeQuietly();
            try {
                return invoke(name, argsMap);
            } catch (RuntimeException e2) {
                throw new AgoraUnavailableException("Agora unreachable for " + name + ": " + e2.getMessage(), e2);
            }
        }
    }

    private JsonNode invoke(String name, Map<String, Object> argsMap) {
        McpSyncClient c = ensureClient();
        McpSchema.CallToolResult res = c.callTool(new McpSchema.CallToolRequest(name, argsMap));
        boolean isError = Boolean.TRUE.equals(res.isError());
        if (res.content() == null || res.content().isEmpty()) {
            throw new AgoraUnavailableException("empty Agora response for " + name);
        }
        String text = ((McpSchema.TextContent) res.content().getFirst()).text();
        return parseToolText(text, isError);
    }

    private McpSyncClient ensureClient() {
        McpSyncClient local = client;
        if (local != null) return local;
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .httpRequestCustomizer((b, method, uri, body, ctx) ->
                        b.setHeader("Authorization", "Bearer " + token))
                .build();
        McpSyncClient built = McpClient.sync(transport).build();
        built.initialize();
        client = built;
        return built;
    }

    private void closeQuietly() {
        McpSyncClient local = client;
        client = null;
        if (local != null) {
            try { local.closeGracefully(); } catch (RuntimeException ignored) { /* best effort */ }
        }
    }

    /** Package-private: parse the tool's text payload; throw if it is an error/unavailable envelope. */
    static JsonNode parseToolText(String text, boolean isError) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new AgoraUnavailableException("unparseable Agora response: " + e.getMessage(), e);
        }
        if (isError || (node.has("available") && !node.path("available").asBoolean(true))) {
            throw new AgoraUnavailableException("Agora tool error: " + node.path("error").asString(text));
        }
        return node;
    }
}
