package de.visterion.dracul.marketdata;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
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
    private final long timeoutMs;
    private final long connectTimeoutMs;
    private final AgoraToolTimeouts toolTimeouts;
    /**
     * One client per distinct request budget. The MCP sync client pins its request timeout at
     * build time, so a per-tool budget cannot be expressed on the call — it has to be a second
     * client. In practice this map holds two entries (the global default and the Form-4 budget);
     * it is keyed by the budget rather than by the tool name so tools that share one only ever
     * open one session. Guarded by {@code this}, like every other field here.
     */
    private final Map<Long, McpSyncClient> clients = new HashMap<>();

    @Autowired
    public AgoraClient(@Value("${dracul.agora.base-url:http://agora:8080}") String baseUrl,
                       @Value("${dracul.agora.token:}") String token,
                       @Value("${dracul.agora.timeout-ms:25000}") long timeoutMs,
                       @Value("${dracul.agora.connect-timeout-ms:5000}") long connectTimeoutMs,
                       AgoraToolTimeouts toolTimeouts) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeoutMs = timeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.toolTimeouts = toolTimeouts == null ? AgoraToolTimeouts.none() : toolTimeouts;
    }

    /** No per-tool overrides — kept for tests and for any caller that only needs the default. */
    public AgoraClient(String baseUrl, String token, long timeoutMs, long connectTimeoutMs) {
        this(baseUrl, token, timeoutMs, connectTimeoutMs, AgoraToolTimeouts.none());
    }

    /** The request budget this client will spend on {@code tool}. Package-private for tests. */
    long timeoutForTool(String tool) {
        return toolTimeouts.forTool(tool, timeoutMs);
    }

    /** Call an Agora tool by name with JSON args; returns the tool's output JSON. Never returns null. */
    public synchronized JsonNode callTool(String name, JsonNode args) {
        Map<String, Object> argsMap = args == null ? Map.of() : MAPPER.convertValue(args, MAP_TYPE);
        try {
            return attempt(name, argsMap);
        } catch (AgoraUnavailableException e) {
            // Terminal without a retry (empty response / error envelope). Logged here because
            // all 17 facade catch sites swallow this exception without a word — which is why
            // "Agora unreachable" never appeared in a production log despite being the text
            // that ends up in the tool payload.
            //
            // One error envelope is NOT an outage: Agora refusing a document that exceeds its
            // filing-size cap says something about that one filing, and calling it "unreachable"
            // both misleads whoever reads the log and inflates the daily analysis's outage count
            // (_AGORA_FAIL_RE matches on that exact phrase). It still throws — the caller decides
            // how to degrade — but it is named for what it is.
            if (e.filingTooLarge()) {
                log.warn("Agora refused an oversized document for {}: {}", name, e.getMessage());
            } else {
                log.warn("Agora unreachable for {}: {}", name, e.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            // session may be stale — drop the client, reconnect once, retry
            if (isSessionCut(e)) {
                // A cut session is not a fault, it is what an Agora restart looks like from here:
                // measured on prod 2026-08-07, Agora restarted 06:31:18Z and the two calls that
                // were holding a session (search_filings 06:32:14Z, get_form4_transactions
                // 06:35:44Z) both landed here and both succeeded on the retry below. Logging that
                // at WARN put a "failed" next to a call that did not fail. If the retry does not
                // recover, the WARN two lines down says so — that is where the severity belongs.
                log.info("Agora session for {} was cut mid-call ({}) — reconnecting and retrying "
                        + "once; this is the normal shape of an Agora restart", name, e.getMessage());
            } else {
                log.warn("Agora call {} failed ({}); reconnecting", name, e.toString());
            }
            closeQuietly(timeoutForTool(name));
            try {
                return attempt(name, argsMap);
            } catch (RuntimeException e2) {
                log.warn("Agora unreachable for {} after reconnect: {}", name, e2.toString());
                throw new AgoraUnavailableException("Agora unreachable for " + name + ": " + e2.getMessage(), e2);
            }
        }
    }

    /**
     * The MCP library's wording for "the server no longer knows this session", verbatim from prod
     * logs. Matched on the message rather than on a type because mcp-core raises it as a plain
     * {@link RuntimeException}; a miss only costs the old WARN wording, never the retry.
     */
    static boolean isSessionCut(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("MCP session with server terminated")) return true;
        }
        return false;
    }

    /**
     * One attempt: ensure the client, call the tool, extract + parse its text payload. Package-private
     * so the reconnect-once logic in {@link #callTool} can be exercised with a stubbed seam in tests.
     */
    JsonNode attempt(String name, Map<String, Object> argsMap) {
        McpSyncClient c = ensureClient(timeoutForTool(name));
        McpSchema.CallToolResult res = c.callTool(new McpSchema.CallToolRequest(name, argsMap));
        boolean isError = Boolean.TRUE.equals(res.isError());
        if (res.content() == null || res.content().isEmpty()) {
            throw new AgoraUnavailableException("empty Agora response for " + name);
        }
        String text = ((McpSchema.TextContent) res.content().getFirst()).text();
        return parseToolText(text, isError);
    }

    private McpSyncClient ensureClient(long requestTimeoutMs) {
        McpSyncClient local = clients.get(requestTimeoutMs);
        if (local != null) return local;
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                // Connect and request budgets are deliberately separate: a slow-but-alive Agora
                // (get_form4_transactions walks a market-wide EDGAR window under its own 30s
                // aggregate deadline) must be waited out, while a dead one must fail fast.
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .httpRequestCustomizer((b, method, uri, body, ctx) ->
                        b.setHeader("Authorization", "Bearer " + token))
                .build();
        McpSyncClient built = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        built.initialize();
        clients.put(requestTimeoutMs, built);
        return built;
    }

    private void closeQuietly(long requestTimeoutMs) {
        McpSyncClient local = clients.remove(requestTimeoutMs);
        if (local != null) {
            try { local.closeGracefully(); } catch (Exception ignored) { /* best effort */ }
        }
    }

    /**
     * Package-private: parse the tool's text payload; throw only if Agora's ENVELOPE says the call
     * failed.
     *
     * <p>The MCP {@code isError} flag is the sole outage discriminator, and deliberately so. Two
     * different {@code available} flags travel the same wire and mean opposite things: Agora
     * serialises an unavailable {@code ToolResult} as the body {@code {"available":false,...}}
     * AND sets {@code isError}, while {@code get_quote}, {@code get_ohlc} and {@code get_indicators}
     * put a top-level {@code available} inside a SUCCESSFUL payload — used to say "this symbol (or
     * this indicator spec) produced no data". That is a statement about the data, not about the
     * source: an unresolvable symbol or a listing younger than 52 weeks has no quote/range, and
     * Agora answered perfectly well to say so. Treating that flag as an outage threw the body away
     * before the caller could degrade on it, logged healthy responses as "Agora unreachable", and
     * pushed the lazarus source-down heuristic toward a false outage verdict. A genuine failure
     * inside those tools throws upstream and comes back as an error envelope, so nothing is lost by
     * trusting {@code isError} alone.
     */
    static JsonNode parseToolText(String text, boolean isError) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new AgoraUnavailableException("unparseable Agora response: " + e.getMessage(), e);
        }
        if (isError) {
            // REQUEST scope: Agora ANSWERED. Whatever went wrong, it went wrong about this one
            // call — "Yahoo Finance OHLC returned HTTP 404 NOT_FOUND" is Yahoo saying it does not
            // know that symbol, "no CIK for XYZ" is one issuer that will not resolve. Callers that
            // batch must not read either as an outage; a run of them still may (see
            // EnrichmentSourceGuard), and a genuine Agora-side failure that also arrives this way
            // will produce exactly such a run.
            throw new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST,
                    "Agora tool error: " + node.path("error").asString(text), null);
        }
        return node;
    }
}
