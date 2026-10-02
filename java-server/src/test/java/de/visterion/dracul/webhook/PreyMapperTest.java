package de.visterion.dracul.webhook;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreyMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void mapsPreyWithDefaultsAndDiscoveredBy() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"ACME","companyName":"Acme","confidence":0.8,
                      "thesis":"t","signals":["s1"],"risks":["r1"]}]}
            """).path("prey");
        List<Prey> prey = new PreyMapper().map(node, "strigoi-echo", "PEAD", "3m", false);

        assertThat(prey).singleElement().satisfies(p -> {
            assertThat(p.symbol()).isEqualTo("ACME");
            assertThat(p.anomalyType()).isEqualTo("PEAD");
            assertThat(p.horizon()).isEqualTo("3m");
            assertThat(p.discoveredBy()).isEqualTo("strigoi-echo");
            assertThat(p.signals()).containsExactly("s1");
        });
    }

    @Test
    void skipsBlankSymbolWhenRequested() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"","companyName":"x","confidence":0.5}]}
            """).path("prey");
        assertThat(new PreyMapper().map(node, "strigoi-merger", "MERGER_ARB", "3m", true)).isEmpty();
    }

    @Test
    void mapsKillCriteria() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"ACME","companyName":"Acme","confidence":0.8,
                      "thesis":"t","kill_criteria":["Close below 42.50","Deal breaks"]}]}
            """).path("prey");
        List<Prey> prey = new PreyMapper().map(node, "strigoi-merger", "MERGER_ARB", "3m", false);

        assertThat(prey).singleElement().satisfies(p ->
                assertThat(p.killCriteria()).containsExactly("Close below 42.50", "Deal breaks"));
    }

    @Test
    void missingKillCriteriaDefaultsToEmpty() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"ACME","companyName":"Acme","confidence":0.8,"thesis":"t"}]}
            """).path("prey");
        assertThat(new PreyMapper().map(node, "strigoi-echo", "PEAD", "3m", false))
                .singleElement()
                .satisfies(p -> assertThat(p.killCriteria()).isEmpty());
    }

    // --- kill_close_below (spec 2026-10-02 §3.1/§3.3): only a positive JSON number survives ---

    private Prey mapOne(String killCloseBelowJson) throws Exception {
        String field = killCloseBelowJson == null ? "" : ",\"kill_close_below\":" + killCloseBelowJson;
        var node = json.readTree("{\"prey\":[{\"symbol\":\"SYNKL\",\"companyName\":\"Synthetic\","
                + "\"confidence\":0.7,\"thesis\":\"t\"" + field + "}]}").path("prey");
        return new PreyMapper().map(node, "strigoi-echo", "PEAD", "3m", false).getFirst();
    }

    private List<String> warnLinesWhile(ThrowingRunnable body) throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PreyMapper.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @Test
    void killCloseBelow_positiveNumberIsKept() throws Exception {
        assertThat(mapOne("48.2").killCloseBelow()).isEqualByComparingTo("48.2");
        assertThat(mapOne("12").killCloseBelow()).isEqualByComparingTo("12");
    }

    @Test
    void killCloseBelow_absentOrNullIsNullWithoutWarn() throws Exception {
        var warns = warnLinesWhile(() -> {
            assertThat(mapOne(null).killCloseBelow()).isNull();
            assertThat(mapOne("null").killCloseBelow()).isNull();
        });
        assertThat(warns).isEmpty();
    }

    @Test
    void killCloseBelow_unusableValuesAreDroppedWithAWarnNamingSymbolAndRawValue() throws Exception {
        var warns = warnLinesWhile(() -> {
            assertThat(mapOne("0").killCloseBelow()).isNull();
            assertThat(mapOne("-3.5").killCloseBelow()).isNull();
            assertThat(mapOne("\"48,20\"").killCloseBelow()).isNull();
            assertThat(mapOne("true").killCloseBelow()).isNull();
        });
        assertThat(warns).containsExactly(
                "prey SYNKL: kill_close_below ignored, unusable value 0",
                "prey SYNKL: kill_close_below ignored, unusable value -3.5",
                "prey SYNKL: kill_close_below ignored, unusable value \"48,20\"",
                "prey SYNKL: kill_close_below ignored, unusable value true");
    }

    /** 2026-10-02 fix round: {@code 1e400} parses as a DoubleNode whose numeric value overflows
     *  to {@code Infinity} — {@code asString()} then returns "Infinity", which blew up {@code new
     *  BigDecimal(...)} with a {@link NumberFormatException} and turned the whole webhook call
     *  into a 500, losing every prey in the batch. map() must never throw; the value is dropped
     *  with the same WARN as any other unusable value. */
    @Test
    void killCloseBelow_nonFiniteNumberIsDroppedWithoutThrowing() throws Exception {
        var warns = warnLinesWhile(() -> assertThat(mapOne("1e400").killCloseBelow()).isNull());
        assertThat(warns).singleElement()
                .satisfies(w -> assertThat(w).startsWith("prey SYNKL: kill_close_below ignored, unusable value "));
    }

    /** {@code 1e13} is a finite, parseable, strictly-positive BigDecimal — but it overflows
     *  NUMERIC(18,6) in the `prey` table at insert time, which would lose the rest of the batch
     *  on a partial-insert failure. Must be dropped here, before the insert, same as any other
     *  unusable value. */
    @Test
    void killCloseBelow_valueAtOrAboveTheOverflowBoundIsDropped() throws Exception {
        var warns = warnLinesWhile(() -> assertThat(mapOne("1e13").killCloseBelow()).isNull());
        assertThat(warns).singleElement()
                .satisfies(w -> assertThat(w).startsWith("prey SYNKL: kill_close_below ignored, unusable value "));
    }
}
