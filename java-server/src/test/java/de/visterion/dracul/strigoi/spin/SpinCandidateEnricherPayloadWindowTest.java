package de.visterion.dracul.strigoi.spin;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D11: spin ignored the requested window entirely. {@code lookback_days} reached only the EDGAR
 * INGEST search; the RESPOND payload was read straight back from the DB with
 * {@code findActiveUnpromoted(50)} — the WHOLE active table, no date predicate — which is why a
 * 14-day window produced 8 candidates including a filing from ten weeks earlier.
 *
 * <p>The payload must be built for the requested window, and the 50-row cap must be as honest
 * about cutting as the merger cap now is.
 */
class SpinCandidateEnricherPayloadWindowTest {

    private static SpinCandidateRow row(String symbol) {
        return new SpinCandidateRow(1L, "0000000001", symbol, symbol + " Co", "10-12B",
                LocalDate.parse("2026-07-01"), "https://sec/" + symbol,
                null, null, null, false, null, null,
                SpinStatus.REGISTERED, null, null, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z", null, null, null, null);
    }

    private static SpinCandidateRow distributedRow(String symbol, tools.jackson.databind.JsonNode distributedSnapshot) {
        return new SpinCandidateRow(1L, "0000000001", symbol, symbol + " Co", "10-12B",
                LocalDate.parse("2026-07-01"), "https://sec/" + symbol,
                null, null, null, false, null, null,
                SpinStatus.DISTRIBUTED, null, distributedSnapshot, null,
                null, null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z", "2026-08-08T00:00:00Z", null, null, null);
    }

    private SpinCandidateEnricher enricher(SpinCandidateRepository repo) {
        return new SpinCandidateEnricher(repo,
                Mockito.mock(SpinLifecycleReconciler.class),
                Mockito.mock(SpinBalanceSheetSnapshotter.class),
                Mockito.mock(SpinDistributionSnapshotter.class),
                Mockito.mock(SpinValuationSnapshotter.class),
                Mockito.mock(de.visterion.dracul.hunting.agora.AgoraFilings.class),
                Mockito.mock(SpinTermsParser.class),
                new ObjectMapper());
    }

    @Test void payloadIsBuiltForTheRequestedWindow() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        LocalDate since = LocalDate.parse("2026-07-20");
        when(repo.findActiveUnpromotedInWindow(eq(since), anyInt())).thenReturn(List.of(row("SPN")));

        SpinPayload payload = enricher(repo).payload(since);

        assertThat(payload.candidates()).extracting(EnrichedSpinCandidate::symbol).containsExactly("SPN");
        assertThat(payload.truncated()).isFalse();
        // The window must be the one that was asked for, not a stand-in.
        Mockito.verify(repo).findActiveUnpromotedInWindow(eq(since), anyInt());
    }

    @Test void aFullPageIsReportedAsTruncated() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        List<SpinCandidateRow> full = new ArrayList<>(IntStream.range(0, SpinCandidateEnricher.RESPONSE_LIMIT)
                .mapToObj(i -> row("S" + i)).toList());
        when(repo.findActiveUnpromotedInWindow(any(), anyInt())).thenReturn(full);

        SpinPayload payload = enricher(repo).payload(LocalDate.parse("2026-07-20"));

        assertThat(payload.candidates()).hasSize(SpinCandidateEnricher.RESPONSE_LIMIT);
        assertThat(payload.truncated()).isTrue();
    }

    // --- distributionDateConfirmed wire mapping (2026-08-08 fix follow-up review) ---

    @Test void explicitFalseInTheSnapshotIsCarriedThroughAsFalse() {
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = mapper.readTree(
                "{\"spincoMarketCapMillions\":150.0,\"daysSinceDistribution\":4,\"distributionDateConfirmed\":false}");
        when(repo.findActiveUnpromotedInWindow(any(), anyInt()))
                .thenReturn(List.of(distributedRow("SPN", snapshot)));

        SpinPayload payload = enricher(repo).payload(LocalDate.parse("2026-07-20"));

        assertThat(payload.candidates()).hasSize(1);
        assertThat(payload.candidates().getFirst().distributionDateConfirmed()).isFalse();
    }

    @Test void aSnapshotPredatingTheFieldReadsAsUnconfirmedNotAsAnError() {
        // A distributed_snapshot persisted before this fix shipped has no
        // distributionDateConfirmed key at all (not merely a null value) — boolOrFalse must
        // treat an absent field the same as an explicit false, never throw or propagate null.
        SpinCandidateRepository repo = Mockito.mock(SpinCandidateRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = mapper.readTree(
                "{\"spincoMarketCapMillions\":150.0,\"daysSinceDistribution\":4}");
        when(repo.findActiveUnpromotedInWindow(any(), anyInt()))
                .thenReturn(List.of(distributedRow("OLD", snapshot)));

        SpinPayload payload = enricher(repo).payload(LocalDate.parse("2026-07-20"));

        assertThat(payload.candidates()).hasSize(1);
        assertThat(payload.candidates().getFirst().distributionDateConfirmed()).isFalse();
    }
}
