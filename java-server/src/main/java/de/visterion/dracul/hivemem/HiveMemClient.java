package de.visterion.dracul.hivemem;

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

import java.time.Duration;
import java.util.Map;

/**
 * MCP client for HiveMem's Streamable-HTTP front-door. Near-copy of
 * {@link de.visterion.dracul.marketdata.AgoraClient} with one addition: two call postures.
 * Write posture (single attempt, no reconnect-retry) keeps a HiveMem outage from costing
 * 2x timeout inside a synchronous completion hook; read posture (reconnect-once) matches
 * AgoraClient's existing behavior for pre-fetch reads.
 */
@Component
public class HiveMemClient {

    private static final Logger log = LoggerFactory.getLogger(HiveMemClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String baseUrl;
    private final String token;
    private final long timeoutMs;
    private volatile McpSyncClient client;

    public HiveMemClient(@Value("${dracul.hivemem.base-url:http://hivemem:8421}") String baseUrl,
                          @Value("${dracul.hivemem.mcp-token:}") String token,
                          @Value("${dracul.hivemem.timeout-ms:8000}") long timeoutMs) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeoutMs = timeoutMs;
    }

    /** Read posture: reconnect-once on failure (mirrors AgoraClient.callTool). */
    public synchronized JsonNode callToolRead(String name, JsonNode args) {
        Map<String, Object> argsMap = toMap(args);
        try {
            return attempt(name, argsMap);
        } catch (HiveMemUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("HiveMem read {} failed ({}); reconnecting", name, e.toString());
            closeQuietly();
            try {
                return attempt(name, argsMap);
            } catch (RuntimeException e2) {
                throw new HiveMemUnavailableException(
                        "HiveMem unreachable for " + name + ": " + e2.getMessage(), e2);
            }
        }
    }

    /** Write posture: single attempt, no reconnect-retry. */
    public synchronized JsonNode callToolWrite(String name, JsonNode args) {
        Map<String, Object> argsMap = toMap(args);
        try {
            return attempt(name, argsMap);
        } catch (HiveMemUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            closeQuietly();
            throw new HiveMemUnavailableException(
                    "HiveMem unreachable for " + name + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> toMap(JsonNode args) {
        return args == null ? Map.of() : MAPPER.convertValue(args, MAP_TYPE);
    }

    /**
     * One attempt: ensure the client, call the tool, extract + parse its text payload. Package-private
     * so the retry logic in {@link #callToolRead} and the single-attempt logic in
     * {@link #callToolWrite} can be exercised with a stubbed seam in tests.
     */
    JsonNode attempt(String name, Map<String, Object> argsMap) {
        McpSyncClient c = ensureClient();
        McpSchema.CallToolResult res = c.callTool(new McpSchema.CallToolRequest(name, argsMap));
        boolean isError = Boolean.TRUE.equals(res.isError());
        if (res.content() == null || res.content().isEmpty()) {
            throw new HiveMemUnavailableException("empty HiveMem response for " + name);
        }
        String text = ((McpSchema.TextContent) res.content().getFirst()).text();
        return parseToolText(text, isError);
    }

    private McpSyncClient ensureClient() {
        McpSyncClient local = client;
        if (local != null) return local;
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .httpRequestCustomizer((b, method, uri, body, ctx) ->
                        b.setHeader("Authorization", "Bearer " + token))
                .build();
        McpSyncClient built = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(timeoutMs))
                // The SDK defaults initializationTimeout to 20s independently of requestTimeout;
                // without pinning it here, a hung connect/handshake would not honor timeoutMs.
                .initializationTimeout(Duration.ofMillis(timeoutMs))
                .build();
        built.initialize();
        client = built;
        return built;
    }

    private void closeQuietly() {
        McpSyncClient local = client;
        client = null;
        if (local != null) {
            try {
                local.closeGracefully();
            } catch (Exception ignored) {
                /* best effort */
            }
        }
    }

    /** Package-private: parse the tool's text payload; throw if it is an error/unavailable envelope. */
    static JsonNode parseToolText(String text, boolean isError) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new HiveMemUnavailableException("unparseable HiveMem response: " + e.getMessage(), e);
        }
        if (isError || (node.has("available") && !node.path("available").asBoolean(true))) {
            throw new HiveMemUnavailableException("HiveMem tool error: " + node.path("error").asString(text));
        }
        return node;
    }
}
