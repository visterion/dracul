package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotEquitySnapshotJobTest {

    // 2026-01-05 is a Monday; 21:45Z is (roughly) the daily cron's firing time. Deliberately
    // NOT on a minute boundary: Instant.parse("...T21:45:00Z").truncatedTo(MINUTES) would be the
    // identity, so the intraday test below would stay green even if the truncation were loosened
    // to SECONDS/MILLIS or deleted outright. Off-boundary seconds/millis make the test assert
    // something the truncation actually has to do.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-05T21:45:37.123Z"), ZoneOffset.UTC);
    private static final Instant DAY_LABEL = Instant.parse("2026-01-05T00:00:00Z");

    // Package-private constructor with an injectable Clock — the pattern EntryExpiryService:65
    // and ExecutorWebhookController:185-188 already use. There is deliberately NO global Clock
    // bean in this codebase (ExecutorDefaults:19-23 names its own "executorClock" to avoid
    // exactly that ambiguity).
    private DepotEquitySnapshotJob job(AgoraDepotClient client, DepotEquitySnapshotRepository repo) {
        return new DepotEquitySnapshotJob(client, repo, CLOCK);
    }

    private DepotConnection conn(String id) {
        return new DepotConnection(id, "saxo", "paper", "ok", null);
    }

    private DepotAccount account(BigDecimal equity, BigDecimal cash, String currency) {
        return new DepotAccount(cash, equity, cash, currency, "ACTIVE", null);
    }

    @Test
    void writesOneDailyRowPerConnectionLabelledWithTheCalendarDay() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1"), conn("conn-2")));
        when(client.account(anyString()))
                .thenReturn(account(new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR"));
        when(repo.upsert(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));

        job(client, repo).captureDaily();

        // captureDaily is the @Scheduled entry point: it is void, and its job is to derive the
        // DAILY label from the clock. The tally is asserted through capture() below.
        verify(repo).upsert(eq("conn-1"), eq(DAY_LABEL), eq("DAILY"),
                eq(new BigDecimal("100.00")), eq(new BigDecimal("40.00")), eq("EUR"));
        verify(repo).upsert(eq("conn-2"), eq(DAY_LABEL), eq("DAILY"),
                eq(new BigDecimal("100.00")), eq(new BigDecimal("40.00")), eq("EUR"));
    }

    @Test
    void tallyCountsWrittenAndSkippedConnections() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1"), conn("conn-2")));
        when(client.account(anyString()))
                .thenReturn(account(new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR"));
        when(repo.upsert(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));

        var result = job(client, repo).capture("DAILY", DAY_LABEL);

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void intradayRowIsLabelledWithTheRunTimeTruncatedToTheMinute() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1")));
        when(client.account(anyString()))
                .thenReturn(account(new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR"));
        when(repo.upsert(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));

        job(client, repo).captureIntraday();

        verify(repo).upsert(eq("conn-1"), eq(Instant.parse("2026-01-05T21:45:00Z")),
                eq("INTRADAY"), any(), any(), anyString());
    }

    @Test
    void aThrowingConnectionIsSkippedAndTheOthersStillGetTheirRow() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1"), conn("conn-2")));
        when(client.account("conn-1")).thenThrow(new DepotUnavailableException("agora down"));
        when(client.account("conn-2"))
                .thenReturn(account(new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR"));
        when(repo.upsert(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));

        var result = job(client, repo).capture("DAILY", DAY_LABEL);

        assertThat(result.written()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(repo, never()).upsert(eq("conn-1"), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void nullEquityWritesNothingAndDoesNotThrow() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1")));
        when(client.account("conn-1")).thenReturn(account(null, new BigDecimal("40.00"), "EUR"));

        var result = job(client, repo).capture("DAILY", DAY_LABEL);

        assertThat(result.written()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(repo, never()).upsert(anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void nullCashWritesNothing() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1")));
        when(client.account("conn-1")).thenReturn(account(new BigDecimal("100.00"), null, "EUR"));

        assertThat(job(client, repo).capture("DAILY", DAY_LABEL).written()).isZero();
        verify(repo, never()).upsert(anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void nullCurrencyWritesNothing() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of(conn("conn-1")));
        when(client.account("conn-1"))
                .thenReturn(account(new BigDecimal("100.00"), new BigDecimal("40.00"), null));

        assertThat(job(client, repo).capture("DAILY", DAY_LABEL).written()).isZero();
        verify(repo, never()).upsert(anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void aThrowingConnectionEnumerationWritesNothingAndDoesNotThrow() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenThrow(new DepotUnavailableException("agora down"));

        var result = job(client, repo).capture("DAILY", DAY_LABEL);

        assertThat(result.written()).isZero();
        verify(repo, never()).upsert(anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void anEmptyConnectionListWritesNothing() {
        AgoraDepotClient client = mock(AgoraDepotClient.class);
        DepotEquitySnapshotRepository repo = mock(DepotEquitySnapshotRepository.class);
        when(client.listConnections()).thenReturn(List.of());

        assertThat(job(client, repo).capture("DAILY", DAY_LABEL).written()).isZero();
        verify(repo, never()).upsert(anyString(), any(), anyString(), any(), any(), anyString());
    }
}
