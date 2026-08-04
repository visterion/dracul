package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BLOCKER 2: an {@code unavailable} data source has to be distinguishable from a quiet market on
 * a channel somebody actually reads.
 *
 * <p><b>The hole.</b> D7 made an empty or unfetchable lazarus universe report
 * {@code data_source_health.status = "unavailable"} instead of healthy-with-zero. Nothing
 * downstream noticed:
 * <ul>
 *   <li>{@link HuntController#handleFetch} logged the fetch line at INFO, and set neither
 *       {@code partial} nor {@code truncated};</li>
 *   <li>the Vistierie run status stayed {@code done};</li>
 *   <li>the prompt turns {@code unavailable} into {@code {"prey": []}};</li>
 *   <li>the daily analysis inspected only {@code partial}/{@code truncated}
 *       ({@code _degraded_fetches}), matched a log line this path never emits
 *       ({@code _TOOL_GUARD_RES}), and treated {@code done} as success
 *       ({@code FAILED_STATUSES}).</li>
 * </ul>
 * A dead data source produced a green run, empty prey and total silence — the identical failure
 * with a new label, plus a severity inversion in which the milder {@code partial} raised an alarm
 * and the total outage did not.
 *
 * <p><b>The channel chosen, and why.</b> The fetch line goes to WARN when the status is
 * {@code unavailable}, and the daily analysis's {@code _degraded_fetches} — which already parses
 * {@code data_source_health} out of every recorded tool call — additionally reports
 * {@code status == "unavailable"} and raises that occurrence to HIGH.
 *
 * <p>That pairing is deliberate. The DB channel is the load-bearing one: {@code run_tool_calls}
 * is durable, structured, and immune to log-format drift (the drift that made
 * {@code _TOOL_GUARD_RES} match nothing on this path in the first place). The log line is the
 * cheap corroborating channel. Neither the run status nor the health flags are touched:
 * <ul>
 *   <li>flipping {@code partial}/{@code truncated} would be a lie — the answer is not
 *       incomplete, it is absent — and would blend the outage back into the same bucket as the
 *       milder degradation, which is the inversion being fixed;</li>
 *   <li>failing the run would discard every prey the hunter already produced (the exact reason
 *       the tool-endpoint guard answers 200-with-unavailable instead of 4xx), and would turn a
 *       transient upstream hiccup into a paging storm.</li>
 * </ul>
 * The daily analysis runs once a day over a 24 h window, so a single transient
 * {@code unavailable} shows up as one line in a report rather than a page, while a source that
 * is genuinely down stays visible on every run of the day.
 */
class HuntControllerUnavailableLoudnessTest {

    private static final class Probe extends HuntController {
        private final DataSourceResult<?> result;

        Probe(DataSourceResult<?> result) {
            super("t", null, new ToolFetchCache(new AgentToolCatalog(List.of()), 0), null, null);
            this.result = result;
            // patternRepo / signalEmitter are @Autowired FIELDS, so a hand-built controller leaves
            // them null and handleFetch NPEs inside its own guard — which would then log a WARN of
            // its own and make every level assertion here meaningless. Supply empty providers.
            org.springframework.test.util.ReflectionTestUtils.setField(
                    this, HuntController.class, "patternRepo", emptyProvider(), null);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    this, HuntController.class, "signalEmitter", emptyProvider(), null);
        }

        @SuppressWarnings("unchecked")
        private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
            return (org.springframework.beans.factory.ObjectProvider<T>)
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        }

        @Override protected String agentName() { return "strigoi-probe"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> input) { return result; }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "probe"; }
    }

    @Test void anUnavailableFetchIsLoggedAtWarn() {
        var probe = new Probe(new DataSourceResult<>(List.of(),
                DataSourceHealth.unavailable("agora", "universe empty")));

        var events = capture(probe, () -> probe.handleFetch("Bearer t", Map.of()));

        assertThat(events)
                .as("an unavailable data source must not be logged at the same volume as a "
                        + "healthy fetch — that is what made it invisible")
                .anyMatch(e -> "WARN".equals(e.getLevel().toString())
                        && e.getFormattedMessage().contains("status=unavailable"));
    }

    @Test void aHealthyFetchStaysAtInfo() {
        var probe = new Probe(DataSourceResult.healthy("agora", List.of("a")));

        var events = capture(probe, () -> probe.handleFetch("Bearer t", Map.of()));

        assertThat(events).isNotEmpty();
        assertThat(events).allMatch(e -> "INFO".equals(e.getLevel().toString()));
    }

    /** A merely degraded fetch keeps its existing volume: the alarm for it already exists, and
     *  raising it too would flatten the severity distinction this fix restores. */
    @Test void aPartialFetchStaysAtInfo() {
        var probe = new Probe(new DataSourceResult<>(List.of("a"),
                DataSourceHealth.degraded("agora", "some rows dropped", true, false)));

        var events = capture(probe, () -> probe.handleFetch("Bearer t", Map.of()));

        assertThat(events).allMatch(e -> "INFO".equals(e.getLevel().toString()));
    }

    /** The tool response must still carry the unavailable status through untouched — the log
     *  level is an ADDITION, never a substitute for the machine-readable channel. */
    @Test void theUnavailableStatusStillReachesTheResponse() {
        var probe = new Probe(new DataSourceResult<>(List.of(),
                DataSourceHealth.unavailable("agora", "universe empty")));

        var body = probe.handleFetch("Bearer t", Map.of()).getBody();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) body.get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) output.get("data_source_health");
        assertThat(health.get("status")).isEqualTo("unavailable");
        assertThat(health).doesNotContainKeys("partial", "truncated");
    }

    private static List<ch.qos.logback.classic.spi.ILoggingEvent> capture(Object owner, Runnable r) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(owner.getClass());
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            r.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
