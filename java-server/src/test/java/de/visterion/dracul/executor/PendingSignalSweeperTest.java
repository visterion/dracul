package de.visterion.dracul.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A PENDING signal nobody evaluates is retired after max-signal-age-days: an executor_decision
 *  row with action=SWEEP (the structural discriminator against place_entry's own SIGNAL_EXPIRED
 *  row, which carries action=null and a decision_log partner) plus status REJECTED. */
class PendingSignalSweeperTest {

    /** Wednesday. */
    private static final Clock WEDNESDAY =
            Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"), ZoneOffset.UTC);

    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final ExecutorDecisionRepository decisionRepo = mock(ExecutorDecisionRepository.class);

    private final PendingSignalSweeper sweeper = new PendingSignalSweeper(
            signalRepo, decisionRepo, new SignalAgePolicy(5), WEDNESDAY);

    private ListAppender<ILoggingEvent> appender;
    private Logger sweeperLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        sweeperLogger = (Logger) LoggerFactory.getLogger(PendingSignalSweeper.class);
        sweeperLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        sweeperLogger.detachAppender(appender);
    }

    private ExecutorSignal pending(String signalId, String symbol, String createdAt) {
        return new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY", 0.7, "SPINOFF",
                List.of(), "3m", new BigDecimal("100"), "PENDING", createdAt);
    }

    /** (1) */
    @Test
    void aSignalOlderThanTheBoundIsRetiredWithASweepDecisionRow() {
        when(signalRepo.findPending(anyInt()))
                .thenReturn(List.of(pending("sig-old", "SWEEPCO", "2026-09-08 04:01:36.733458+00")));

        int retired = sweeper.sweep("run-1");

        assertThat(retired).isEqualTo(1);

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo).insert(captor.capture());
        ExecutorDecision row = captor.getValue();
        assertThat(row.signalId()).isEqualTo("sig-old");
        assertThat(row.symbol()).isEqualTo("SWEEPCO");
        assertThat(row.accepted()).isFalse();
        assertThat(row.rejectReason()).isEqualTo(RejectReason.SIGNAL_EXPIRED.name());
        assertThat(row.action()).isEqualTo(PendingSignalSweeper.ACTION);
        assertThat(row.runId()).isEqualTo("run-1");
        assertThat(row.brokerOrderId()).isNull();
        assertThat(row.vetoTrace()).containsExactly("SIGNAL_EXPIRED:FAIL (6 > 5 days)");
        assertThat(row.rationale())
                .startsWith(PendingSignalSweeper.RATIONALE_PREFIX)
                .endsWith("6 > 5 trading days");

        verify(signalRepo).markStatus("sig-old", "REJECTED");

        // The summary line names the retired symbol: an operator reading the log must be able to
        // tell WHICH signal left the PENDING set without a follow-up DB query.
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("pending sweep: scanned"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage())
                            .isEqualTo("pending sweep: scanned 1 PENDING signal(s), retired 1 older than 5 trading days: SWEEPCO");
                });
    }

    /** (2) — the bound is strict {@code >}, exactly like VetoService's veto #3. */
    @Test
    void aSignalExactlyAtTheBoundIsLeftAlone() {
        when(signalRepo.findPending(anyInt()))
                .thenReturn(List.of(pending("sig-edge", "EDGECO", "2026-09-09 04:01:36+00")));

        assertThat(sweeper.sweep("run-1")).isZero();

        verify(decisionRepo, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(signalRepo, never()).markStatus(anyString(), anyString());
    }

    /** (3) */
    @Test
    void theWeekendIsNotCountedTowardsTheAge() {
        when(signalRepo.findPending(anyInt()))
                .thenReturn(List.of(pending("sig-fri", "FRICO", "2026-09-11 04:01:36+00")));

        assertThat(sweeper.sweep("run-1")).isZero();

        verify(decisionRepo, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(signalRepo, never()).markStatus(anyString(), anyString());
    }

    /** (4) — a row we cannot date is not retired on a guess. */
    @Test
    void anUndatableSignalIsLeftAloneAndWarnedAbout() {
        when(signalRepo.findPending(anyInt()))
                .thenReturn(List.of(pending("sig-blank", "BLANKCO", "   ")));

        assertThat(sweeper.sweep("run-1")).isZero();

        verify(decisionRepo, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(signalRepo, never()).markStatus(anyString(), anyString());
        assertThat(appender.list)
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("sig-blank").contains("BLANKCO");
                });
    }

    /** (5) — each signal is independent; a failed status update leaves the decision row behind and
     *  the still-PENDING signal is retried on the next pass. */
    @Test
    void aFailureOnOneSignalDoesNotStopTheLoop() {
        when(signalRepo.findPending(anyInt())).thenReturn(List.of(
                pending("sig-bad", "BADCO", "2026-09-08 04:01:36+00"),
                pending("sig-ok", "OKCO", "2026-09-08 04:01:36+00")));
        doThrow(new IllegalStateException("synthetic failure"))
                .when(signalRepo).markStatus(eq("sig-bad"), anyString());

        assertThat(sweeper.sweep("run-1")).isEqualTo(1);

        ArgumentCaptor<ExecutorDecision> captor = ArgumentCaptor.forClass(ExecutorDecision.class);
        verify(decisionRepo, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ExecutorDecision::signalId)
                .containsExactly("sig-bad", "sig-ok");
        verify(signalRepo).markStatus("sig-ok", "REJECTED");
    }

    /** (6) — the 50-row window is for the LLM-facing callers; a sweeper must see everything. */
    @Test
    void theWholePendingSetIsScannedNotTheFiftyRowWindow() {
        when(signalRepo.findPending(anyInt())).thenReturn(List.of());

        sweeper.sweep("run-1");

        verify(signalRepo).findPending(Integer.MAX_VALUE);
        verify(signalRepo, never()).findPending(50);
    }

    /** (7) — "the sweep did not run" and "nothing was eligible" must be different observations. */
    @Test
    void theSummaryLineIsLoggedEvenWhenNothingWasEligible() {
        when(signalRepo.findPending(anyInt())).thenReturn(List.of());

        assertThat(sweeper.sweep("run-1")).isZero();

        // getFormattedMessage(), not getMessage(): the latter returns the unformatted pattern.
        // Exact match, not just contains: with nothing retired, the ": <symbols>" suffix must be
        // ABSENT — the line must not end with a trailing "()" or ": " artifact.
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("pending sweep: scanned 0"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage())
                            .isEqualTo("pending sweep: scanned 0 PENDING signal(s), retired 0 older than 5 trading days");
                });
    }

    /** (7b) — a header-less maintenance call writes run_id NULL, which prod has never seen. */
    @Test
    void aNullRunIdMakesTheSummaryLineAWarning() {
        when(signalRepo.findPending(anyInt())).thenReturn(List.of());

        assertThat(sweeper.sweep(null)).isZero();

        // The WARN line must SAY why it is a warning in the message itself, not only in a code
        // comment: a log pipeline or grep that drops the level still has to see the explanation.
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("pending sweep: scanned 0"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage())
                            .isEqualTo("pending sweep: scanned 0 PENDING signal(s), retired 0 older than 5 trading days"
                                    + " — no run id on this maintenance call, so the decision rows carry run_id NULL");
                });
    }
}
