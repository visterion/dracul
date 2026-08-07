package de.visterion.dracul.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Silences ONE measured piece of reactor noise: the terminal error the MCP client library discards
 * when a server-side restart cuts a live session.
 *
 * <p><b>Why this exists.</b> Every Agora redeploy produced an ERROR with a stack trace in Dracul's
 * log that described nothing wrong. Measured on prod (2026-08-07, Agora container restarted
 * 06:31:18Z): the whole container log held exactly two {@code onErrorDropped} lines, at 06:32:14Z
 * and 06:35:44Z, each one immediately preceded by
 * {@code "Server does not recognize session ...; Invalidating"} and by {@code AgoraClient}'s own
 * reconnect line — and both affected calls then succeeded on the retry. The dropped throwable is
 * {@code RuntimeException("Client failed to initialize re-initializing")} raised inside
 * {@code io.modelcontextprotocol.client.LifecycleInitializer:287}: the library re-initializes its
 * session on its own reactor stream, and the stream that lost the race has no subscriber left, so
 * reactor's default hook prints its terminal error at ERROR. Dracul already handled the situation
 * correctly one line above. An ERROR that fires on every deploy and means nothing teaches whoever
 * reads the log to skip ERRORs, which is exactly the signal we cannot afford to blunt.
 *
 * <p><b>Why a global hook is acceptable here, and what stops it going blind.</b>
 * {@code Hooks.onErrorDropped} is process-wide, so it would normally swallow dropped errors from
 * any other reactor user in the same JVM. There is none: reactor enters this application only
 * transitively through {@code mcp-core}, and the only two classes that touch that library are
 * {@code AgoraClient} and {@code HiveMemClient} (no WebFlux, no WebClient, no reactive datastore is
 * on the classpath — verified against {@code pom.xml} and against every {@code import reactor.*} in
 * {@code src/main/java}). The hook therefore only ever sees MCP traffic. And it does not suppress a
 * class of error, it suppresses one exact fingerprint: the message literal above AND an
 * {@code io.modelcontextprotocol} frame somewhere in the throwable chain. Anything else — including
 * a future dropped error from a new reactor user — is re-logged at ERROR with its stack trace, i.e.
 * exactly what reactor's default hook would have done.
 *
 * <p>A per-logger level was the alternative and is worse: reactor emits this from
 * {@code reactor.core.publisher.Operators}, the logger every reactor operator uses for every kind
 * of dropped signal, so muting it would hide unrelated faults with no way to tell them apart.
 *
 * <p>This lives in {@code config} rather than in {@code AgoraClient} because it is a JVM-wide
 * setting, not client state: {@code AgoraClient} must stay a plain, portable MCP client that any
 * harness can lift, and installing a global reactor hook from its constructor would be a
 * side effect on the whole process.
 */
@Configuration
public class ReactorDroppedErrorHook {

    private static final Logger log = LoggerFactory.getLogger(ReactorDroppedErrorHook.class);

    /** Verbatim from mcp-core 2.0.0 {@code LifecycleInitializer:287}; matched, never guessed. */
    static final String RECONNECT_MESSAGE = "Client failed to initialize re-initializing";

    private static final String MCP_PACKAGE = "io.modelcontextprotocol.";

    @PostConstruct
    void install() {
        Hooks.onErrorDropped(ReactorDroppedErrorHook::onDropped);
        log.info("Reactor onErrorDropped hook installed: MCP session re-initialization noise "
                + "is demoted to DEBUG, every other dropped error still logs at ERROR");
    }

    @PreDestroy
    void uninstall() {
        Hooks.resetOnErrorDropped();
    }

    /** Package-private so the routing decision can be asserted without a live reactor stream. */
    static void onDropped(Throwable dropped) {
        if (isMcpReinitializationNoise(dropped)) {
            // DEBUG, not WARN: nothing degraded. The operator-visible account of this event is
            // AgoraClient's own line, which names the tool and says it reconnected.
            log.debug("Dropped MCP re-initialization error after a session cut (expected during an "
                    + "Agora/HiveMem restart; the call itself was retried)", dropped);
            return;
        }
        log.error("Dropped reactor error with no subscriber", dropped);
    }

    /**
     * True only for the measured fingerprint: the mcp-core re-initialization message anywhere in the
     * cause chain, plus an mcp-core frame in some stack trace along that chain. Both are required —
     * the message alone would let any future library reusing that wording through, and an MCP frame
     * alone would swallow genuine MCP faults that nobody subscribed to.
     */
    static boolean isMcpReinitializationNoise(Throwable dropped) {
        boolean sawMessage = false;
        boolean sawMcpFrame = false;
        Throwable t = dropped;
        for (int depth = 0; t != null && depth < 16; depth++) {
            String message = t.getMessage();
            if (message != null && message.contains(RECONNECT_MESSAGE)) sawMessage = true;
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith(MCP_PACKAGE)) {
                    sawMcpFrame = true;
                    break;
                }
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return sawMessage && sawMcpFrame;
    }
}
