package de.visterion.dracul.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the routing decision of {@link ReactorDroppedErrorHook} against hand-built throwables
 * shaped like the ones prod actually dropped. The hook is fed directly rather than through a live
 * reactor stream: {@code Hooks.onErrorDropped} is process-wide state and installing it inside a
 * test would leak into every other test in the same JVM.
 */
class ReactorDroppedErrorHookTest {

    /** The prod fingerprint: mcp-core's re-initialization message, thrown from an mcp-core frame. */
    private static Throwable knownReconnectDrop() {
        RuntimeException cause = new RuntimeException(
                "Client failed to initialize re-initializing");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("io.modelcontextprotocol.client.LifecycleInitializer",
                        "lambda$withInitialization$2", "LifecycleInitializer.java", 287),
                new StackTraceElement("reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber",
                        "onError", "FluxOnErrorResume.java", 95)});
        RuntimeException wrapper = new RuntimeException(
                "java.lang.RuntimeException: Client failed to initialize re-initializing", cause);
        wrapper.setStackTrace(new StackTraceElement[0]);
        return wrapper;
    }

    private static ListAppender<ILoggingEvent> attach() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ReactorDroppedErrorHook.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(ReactorDroppedErrorHook.class)).detachAppender(appender);
    }

    @Test void recognisesTheKnownReconnectDrop() {
        assertThat(ReactorDroppedErrorHook.isMcpReinitializationNoise(knownReconnectDrop())).isTrue();
    }

    @Test void knownReconnectDropDoesNotReachTheLogAboveDebug() {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            ReactorDroppedErrorHook.onDropped(knownReconnectDrop());
            assertThat(appender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
        } finally {
            detach(appender);
        }
    }

    @Test void unrelatedDroppedErrorStillLogsAtError() {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            ReactorDroppedErrorHook.onDropped(new IllegalStateException("something else broke"));
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Dropped reactor error"));
        } finally {
            detach(appender);
        }
    }

    /** The message alone must not be enough — otherwise any future library reusing that wording
     *  would be silenced along with mcp-core's. */
    @Test void sameMessageWithoutAnMcpFrameStillLogsAtError() {
        RuntimeException noMcpFrame = new RuntimeException(
                ReactorDroppedErrorHook.RECONNECT_MESSAGE);
        noMcpFrame.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.Other", "run", "Other.java", 1)});
        assertThat(ReactorDroppedErrorHook.isMcpReinitializationNoise(noMcpFrame)).isFalse();

        ListAppender<ILoggingEvent> appender = attach();
        try {
            ReactorDroppedErrorHook.onDropped(noMcpFrame);
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
        } finally {
            detach(appender);
        }
    }

    /** And an MCP frame alone must not be enough either — a genuine MCP fault nobody subscribed to
     *  is exactly the thing that must keep reaching the log. */
    @Test void otherMcpFailureStillLogsAtError() {
        RuntimeException mcpFault = new RuntimeException("MCP tool call rejected by server");
        mcpFault.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("io.modelcontextprotocol.spec.McpClientSession",
                        "sendRequest", "McpClientSession.java", 1)});
        assertThat(ReactorDroppedErrorHook.isMcpReinitializationNoise(mcpFault)).isFalse();

        ListAppender<ILoggingEvent> appender = attach();
        try {
            ReactorDroppedErrorHook.onDropped(mcpFault);
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
        } finally {
            detach(appender);
        }
    }

    /** A self-referencing cause chain must not spin. */
    @Test void survivesASelfReferencingCauseChain() {
        RuntimeException loop = new RuntimeException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertThat(ReactorDroppedErrorHook.isMcpReinitializationNoise(loop)).isFalse();
    }
}
