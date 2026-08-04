package de.visterion.dracul.webhook;

import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D8: {@code lookback_days} silently degraded to the default for anything that was not a JSON
 * {@code Number} nested under {@code input}. Production proof: the agent sent
 * {@code lookback_days: "20"} — a STRING, because the Vistierie/MCP bridge stringifies tool args,
 * the same stringification that caused the HiveMem bug — and got the 45-day default window with
 * no trace anywhere that the requested value had been dropped.
 */
class HuntControllerLookbackTest {

    /** Minimal concrete hunter; only {@link HuntController#lookbackDays} is exercised. */
    private static final class Probe extends HuntController {
        Probe() { super("t", null, null, null, null); }
        @Override protected String agentName() { return "strigoi-probe"; }
        @Override protected DataSourceResult<?> hunt(Map<String, Object> input) {
            return DataSourceResult.healthy("agora", List.of());
        }
        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "probe"; }
        int lookback(Object raw) {
            Map<String, Object> in = new HashMap<>();
            in.put("lookback_days", raw);
            return lookbackDays(Map.of("input", in), 45, 1, 120);
        }
    }

    private final Probe probe = new Probe();

    @Test void aJsonNumberStillWins() {
        assertThat(probe.lookback(20)).isEqualTo(20);
    }

    @Test void aNumericStringIsAccepted() {
        assertThat(probe.lookback("20")).isEqualTo(20);
    }

    @Test void aPaddedNumericStringIsAccepted() {
        assertThat(probe.lookback("  20  ")).isEqualTo(20);
    }

    @Test void aDecimalStringIsTruncatedLikeNumberIntValue() {
        assertThat(probe.lookback("20.9")).isEqualTo(20);
    }

    @Test void clampingStillApplies() {
        assertThat(probe.lookback("500")).isEqualTo(120);
        assertThat(probe.lookback("0")).isEqualTo(1);
        assertThat(probe.lookback(-7)).isEqualTo(1);
    }

    @Test void genuinelyInvalidInputFallsBackSafely() {
        assertThat(probe.lookback("twenty")).isEqualTo(45);
        assertThat(probe.lookback("")).isEqualTo(45);
        assertThat(probe.lookback(List.of(20))).isEqualTo(45);
        assertThat(probe.lookback(true)).isEqualTo(45);
    }

    @Test void absentValueUsesTheDefaultWithoutComplaint() {
        assertThat(probe.lookbackDays(Map.of(), 45, 1, 120)).isEqualTo(45);
        assertThat(probe.lookbackDays(Map.of("input", Map.of()), 45, 1, 120)).isEqualTo(45);
    }

    /** A value supplied at the TOP level instead of under {@code input} is still not honoured —
     *  the tool contract nests it — but it must not vanish without a word either. */
    @Test void topLevelValueIsRejectedNotHonoured() {
        assertThat(probe.lookbackDays(Map.of("lookback_days", 20), 45, 1, 120)).isEqualTo(45);
    }

    /**
     * An out-of-range value must clamp to the NEAR bound, not wrap through it.
     *
     * <p>The original narrowed to {@code int} BEFORE clamping, so {@code BigDecimal.intValue()}
     * discarded the high-order bits: {@code "2147483648"} (Integer.MAX_VALUE + 1) became
     * {@code -2147483648} and then clamped UP to the minimum — an oversized window silently
     * turned into the smallest possible one, which is precisely the worst case, and it was logged
     * at INFO as an ordinary clamp. {@code "1e18"} and {@code Long.MAX_VALUE} did the same.
     */
    @Test void anOversizedValueClampsToTheMaximumInsteadOfWrappingToTheMinimum() {
        assertThat(probe.lookback("2147483648")).isEqualTo(120);
        assertThat(probe.lookback("1e18")).isEqualTo(120);
        assertThat(probe.lookback(Long.MAX_VALUE)).isEqualTo(120);
        assertThat(probe.lookback("99999999999999999999999999")).isEqualTo(120);
    }

    @Test void anUndersizedValueClampsToTheMinimumInsteadOfWrapping() {
        assertThat(probe.lookback("-2147483649")).isEqualTo(1);
        assertThat(probe.lookback("-1e18")).isEqualTo(1);
        assertThat(probe.lookback(Long.MIN_VALUE)).isEqualTo(1);
    }

    /**
     * A value so far out of range that it cannot have been meant is as much a client bug as
     * {@code "twenty"} is, and the log has to say so at the same volume. An ordinary in-range
     * clamp ({@code 500 -> 120}) stays INFO; an out-of-{@code int} value is a WARN.
     */
    @Test void anOutOfRangeValueIsLoggedAtWarnWhileAnOrdinaryClampStaysInfo() {
        assertThat(levelsFor(() -> probe.lookback("500"))).containsExactly("INFO");
        assertThat(levelsFor(() -> probe.lookback("2147483648"))).containsExactly("WARN");
        assertThat(levelsFor(() -> probe.lookback("1e18"))).containsExactly("WARN");
    }

    private static List<String> levelsFor(Runnable r) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Probe.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            r.run();
            return appender.list.stream().map(e -> e.getLevel().toString()).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
