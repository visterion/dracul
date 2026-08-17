package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.MarketDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
                List.of("dilution"), false, true);
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
        ArgumentCaptor<JsonNode> stored = ArgumentCaptor.forClass(JsonNode.class);
        verify(repo).storeSnapshot(eq(1L), eq(IndexEventStatus.ANNOUNCED), stored.capture());
        // Pins the WRITE side of the T3 legacy-detection chain (fix round 3): a freshly written
        // snapshot must carry the confoundersUnknown key even when it is false (demandSnap() below
        // has confoundersUnknown=false). If a future change (e.g. a global @JsonInclude(NON_DEFAULT),
        // or boxing the record component from boolean to Boolean without care) ever drops a false
        // value from serialization, every HEALTHY row would silently read back as "unknown" on the
        // next enrichment pass — the inversion this task removed, now hitting every clean row
        // instead of only the down ones, and nothing would go red without this assertion.
        assertThat(stored.getValue().path("confoundersUnknown").isBoolean())
                .as("a freshly stored ANNOUNCED snapshot must serialize confoundersUnknown even when false")
                .isTrue();
        assertThat(stored.getValue().path("confoundersUnknown").asBoolean()).isFalse();
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
        // No "confoundersUnknown" key: this pins the pre-T3-fix-round-2 snapshot shape. A missing
        // key must NOT read as "confirmed clean" — see confoundersUnknownDefaultsTrueOnALegacySnapshot.
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

    // --- T3 fix round 2: confoundersUnknown must survive to the wire, in all three shapes ---

    @Test void confoundersUnknownDefaultsTrueOnALegacySnapshot() {
        // A snapshot written before the confoundersUnknown flag existed carries no such key. The
        // key's absence must default to "unknown" (true), never to "confirmed clean" (false) — the
        // true state of the news source at write time was simply never recorded.
        JsonNode ann = mapper.readTree("{\"confounders\":[]}");
        IndexEventRow persisted = new IndexEventRow(7, "LEGACY", "Legacy Co", "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(10), IndexEventStatus.ANNOUNCED,
                ann, null, null, null, null, null, null, null, null);
        when(repo.findActiveUnpromoted(anyInt())).thenReturn(List.of(persisted));

        EnrichedIndexEvent e = enricher.payload().get(0);

        assertThat(e.confounders()).isEmpty();
        assertThat(e.confoundersUnknown()).isTrue();
    }

    @Test void confoundersUnknownIsNullWhenTheAnnouncedStageHasNotBeenEnrichedYet() {
        // No ANNOUNCED snapshot at all (row not yet enriched): null, matching every other
        // stage-gated field's "not yet available" convention — NOT "unknown".
        IndexEventRow persisted = new IndexEventRow(7, "FRESH", "Fresh Co", "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(10), IndexEventStatus.ANNOUNCED,
                null, null, null, null, null, null, null, null, null);
        when(repo.findActiveUnpromoted(anyInt())).thenReturn(List.of(persisted));

        EnrichedIndexEvent e = enricher.payload().get(0);

        assertThat(e.confounders()).isNull();
        assertThat(e.confoundersUnknown()).isNull();
    }

    @Test void confoundersUnknownReadsThroughWhenTheSnapshotSetsItExplicitly() {
        JsonNode annUnknown = mapper.readTree("{\"confounders\":[],\"confoundersUnknown\":true}");
        JsonNode annScanned = mapper.readTree("{\"confounders\":[\"dilution\"],\"confoundersUnknown\":false}");
        IndexEventRow down = new IndexEventRow(7, "DOWN", "Down Co", "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(10), IndexEventStatus.ANNOUNCED,
                annUnknown, null, null, null, null, null, null, null, null);
        IndexEventRow up = new IndexEventRow(8, "UP", "Up Co", "sp500", "add", "sp_press",
                TODAY.minusDays(5), TODAY.plusDays(10), IndexEventStatus.ANNOUNCED,
                annScanned, null, null, null, null, null, null, null, null);
        when(repo.findActiveUnpromoted(anyInt())).thenReturn(List.of(down, up));

        List<EnrichedIndexEvent> payload = enricher.payload();

        EnrichedIndexEvent downEvent = payload.stream().filter(e -> e.symbol().equals("DOWN")).findFirst().get();
        EnrichedIndexEvent upEvent = payload.stream().filter(e -> e.symbol().equals("UP")).findFirst().get();
        assertThat(downEvent.confoundersUnknown()).isTrue();
        assertThat(downEvent.confounders()).isEmpty();
        assertThat(upEvent.confoundersUnknown()).isFalse();
        assertThat(upEvent.confounders()).containsExactly("dilution");
    }
}
