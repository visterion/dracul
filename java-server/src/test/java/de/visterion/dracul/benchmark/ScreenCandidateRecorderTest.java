package de.visterion.dracul.benchmark;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenCandidateRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-13T05:00:00Z");

    private final ScreenCandidateRepository repo = mock(ScreenCandidateRepository.class);
    private final JsonMapper json = new JsonMapper();
    private final ScreenCandidateRecorder recorder =
            new ScreenCandidateRecorder(repo, json, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ScreenCandidateRecorder.class);

    /** Same component names as EnrichedMergerCandidate, so the live serialisation path is real. */
    record SynMerger(String symbol, String companyName, BigDecimal offerPrice, BigDecimal spreadPercent) {}

    private static List<SynMerger> threeItems() {
        return List.of(
                new SynMerger("SYNA", "Synthetic A Corp", new BigDecimal("12.50"), new BigDecimal("3.1")),
                new SynMerger("", "Synthetic Corp (SYNB, SYNB-PA)", null, null),
                new SynMerger(null, "Synthetic Holdings  (CIK 0000000001)", null, null));
    }

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void recordSerialisesTheItemsAndLogsStoredDuplicateAndSymbolLess() {
        when(repo.insert(eq("strigoi-merger"), eq(NOW), any(), eq("LIVE"))).thenReturn(true, false);

        recorder.record("strigoi-merger", ScreenItemMappers.MERGER, threeItems());

        ArgumentCaptor<ScreenItem> items = ArgumentCaptor.forClass(ScreenItem.class);
        verify(repo, times(2)).insert(eq("strigoi-merger"), eq(NOW), items.capture(), eq("LIVE"));
        assertThat(items.getAllValues()).extracting(ScreenItem::symbol).containsExactly("SYNA", "SYNB");
        assertThat(items.getAllValues().getFirst().extra().path("offerPrice").decimalValue())
                .isEqualByComparingTo("12.50");
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage())
                    .isEqualTo("screen-candidate capture strigoi-merger: stored=1 duplicate=1 symbol-less=1");
        });
    }

    @Test
    void storeReturnsTheCountsAndPassesTheSource() {
        when(repo.insert(any(), any(), any(), eq("BACKFILL"))).thenReturn(true, false);
        List<JsonNode> nodes = new ArrayList<>();
        json.valueToTree(threeItems()).forEach(nodes::add);

        CaptureCounts c = recorder.store("strigoi-merger", Instant.parse("2026-07-14T08:00:00Z"),
                ScreenItemMappers.MERGER, nodes, ScreenCandidateRecorder.SOURCE_BACKFILL);

        assertThat(c).isEqualTo(new CaptureCounts(1, 1, 1));
    }

    @Test
    void aFailingRepositoryIsSwallowedAndLoggedWithTheStablePrefix() {
        when(repo.insert(any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic db failure"));

        assertThatCode(() -> recorder.record("strigoi-merger", ScreenItemMappers.MERGER, threeItems()))
                .doesNotThrowAnyException();

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).startsWith("screen-candidate capture failed for strigoi-merger");
            // The exception is passed as the last argument, so the stack trace is logged too.
            assertThat(e.getThrowableProxy()).isNotNull();
            assertThat(e.getThrowableProxy().getMessage()).isEqualTo("synthetic db failure");
        });
    }
}
