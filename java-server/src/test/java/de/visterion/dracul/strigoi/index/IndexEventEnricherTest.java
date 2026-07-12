package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.MarketDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class IndexEventEnricherTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 12);

    private final ObjectMapper mapper = new ObjectMapper();
    private IndexEventRepository repo;
    private IndexDemandSnapshotter demand;
    private IndexDriftSnapshotter drift;
    private IndexEventEnricher enricher;

    @BeforeEach
    void setUp() {
        repo = mock(IndexEventRepository.class);
        demand = mock(IndexDemandSnapshotter.class);
        drift = mock(IndexDriftSnapshotter.class);
        enricher = new IndexEventEnricher(repo, demand, drift, mapper);
        when(demand.snapshot(any(), any(), any())).thenReturn(demandSnap());
        when(drift.snapshot(any(), any(), any(), any())).thenReturn(driftSnap());
    }

    private static IndexEventRow row(long id, IndexEventStatus status) {
        return new IndexEventRow(id, "SYM" + id, "Co " + id, "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(5), status,
                null, null, null, null, null, null, null, null, null);
    }

    private static IndexDemandSnapshotter.IndexDemandSnapshot demandSnap() {
        return new IndexDemandSnapshotter.IndexDemandSnapshot(
                BigDecimal.valueOf(100000), 6000.0, 1000L, 0.02, 5000.0, 11500.0, 11500.0,
                List.of("dilution"), true);
    }

    private static IndexDriftSnapshotter.IndexDriftSnapshot driftSnap() {
        return new IndexDriftSnapshotter.IndexDriftSnapshot(10.0, -5.0, true, 5, true);
    }

    private void queue(IndexEventRow... rows) {
        when(repo.findNonTerminalOldestCheckedFirst(anyInt())).thenReturn(List.of(rows));
    }

    private IndexLifecycleReconciler.ReconcileResult noneTransitioned() {
        return IndexLifecycleReconciler.ReconcileResult.empty();
    }

    @Test void announcedRowGetsDemandSnapshotUnderAnnouncedColumn() {
        queue(row(1, IndexEventStatus.ANNOUNCED));

        enricher.enrich(noneTransitioned(), TODAY);

        verify(demand).snapshot(eq("SYM1"), eq("sp500"), eq(TODAY.minusDays(5)));
        verify(repo).storeSnapshot(eq(1L), eq(IndexEventStatus.ANNOUNCED), any(JsonNode.class));
        verify(drift, never()).snapshot(any(), any(), any(), any());
    }

    @Test void effectiveAndPostRowsGetDriftSnapshotUnderPostColumn() {
        queue(row(1, IndexEventStatus.EFFECTIVE), row(2, IndexEventStatus.POST));

        enricher.enrich(noneTransitioned(), TODAY);

        verify(drift).snapshot(eq("SYM1"), any(), any(), eq(TODAY));
        verify(drift).snapshot(eq("SYM2"), any(), any(), eq(TODAY));
        verify(repo).storeSnapshot(eq(1L), eq(IndexEventStatus.POST), any(JsonNode.class));
        verify(repo).storeSnapshot(eq(2L), eq(IndexEventStatus.POST), any(JsonNode.class));
        verify(demand, never()).snapshot(any(), any(), any());
    }

    @Test void priceSourceOutageShortCircuitsTheRestOfTheBatch() {
        queue(row(1, IndexEventStatus.ANNOUNCED), row(2, IndexEventStatus.ANNOUNCED));
        when(demand.snapshot(eq("SYM1"), any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down"));

        enricher.enrich(noneTransitioned(), TODAY);

        verify(demand).snapshot(eq("SYM1"), any(), any());
        verify(demand, never()).snapshot(eq("SYM2"), any(), any());   // source down -> skipped
        verify(repo).touchLastChecked(1L);
        verify(repo, never()).storeSnapshot(eq(2L), any(), any());
    }

    @Test void symbolSpecificPriceMissDegradesOneRowAndContinues() {
        // NOT_FOUND is symbol-specific, not an availability outage -> the batch keeps going.
        queue(row(1, IndexEventStatus.ANNOUNCED), row(2, IndexEventStatus.ANNOUNCED));
        when(demand.snapshot(eq("SYM1"), any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no bars"));

        enricher.enrich(noneTransitioned(), TODAY);

        verify(repo).touchLastChecked(1L);                            // row 1 degraded
        verify(demand).snapshot(eq("SYM2"), any(), any());            // row 2 still processed
        verify(repo).storeSnapshot(eq(2L), eq(IndexEventStatus.ANNOUNCED), any(JsonNode.class));
    }

    @Test void terminalRowsAreOnlyTouched() {
        queue(row(1, IndexEventStatus.CLOSED));

        enricher.enrich(noneTransitioned(), TODAY);

        verify(repo).touchLastChecked(1L);
        verify(demand, never()).snapshot(any(), any(), any());
        verify(drift, never()).snapshot(any(), any(), any(), any());
    }

    @Test void payloadMapsPersistedSnapshotFieldsToWire() {
        JsonNode ann = mapper.readTree(
                "{\"adv\":100000,\"marketCap\":6000.0,\"avgVolume20d\":1000,\"idiosyncraticVol\":0.02,"
                + "\"freeFloatProxyMillions\":5000.0,\"demandToAdvRatioEstimate\":11500.0,"
                + "\"confounders\":[\"dilution\"]}");
        IndexEventRow persisted = new IndexEventRow(7, "NEWO", "NewCo", "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(10), IndexEventStatus.ANNOUNCED,
                ann, null, null, null, null, null, null, null, null);
        when(repo.findActiveUnpromoted(anyInt())).thenReturn(List.of(persisted));

        List<EnrichedIndexEvent> payload = enricher.payload();

        assertThat(payload).hasSize(1);
        EnrichedIndexEvent e = payload.get(0);
        assertThat(e.symbol()).isEqualTo("NEWO");
        assertThat(e.status()).isEqualTo("ANNOUNCED");
        assertThat(e.adv()).isEqualByComparingTo("100000");
        assertThat(e.marketCap()).isEqualTo(6000.0);
        assertThat(e.avgVolume20d()).isEqualTo(1000L);
        assertThat(e.idiosyncraticVol()).isEqualTo(0.02);
        assertThat(e.freeFloatProxyMillions()).isEqualTo(5000.0);
        assertThat(e.demandToAdvRatioEstimate()).isEqualTo(11500.0);
        assertThat(e.confounders()).containsExactly("dilution");
        assertThat(e.runUpPct()).isNull();                            // no post snapshot yet
    }
}
