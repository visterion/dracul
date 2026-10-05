package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MomentumRankingServiceTest {

    private static final MomentumSettings SETTINGS = new MomentumSettings(10, 10, 3, 480,
            new BigDecimal("0.95"), new BigDecimal("0.025"), 252, 21, new BigDecimal("0.35"),
            new BigDecimal("5"), 1, Set.of("SYN001"), "depot-1");

    private final MomentumRepository repo = mock(MomentumRepository.class);
    private final AgoraIndexConstituents index = mock(AgoraIndexConstituents.class);
    private final MomentumBarsClient bars = mock(MomentumBarsClient.class);
    private final AgoraCompanyData company = mock(AgoraCompanyData.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutorPositionRepository> positions = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutorSignalRepository> signals = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        when(positions.getIfAvailable()).thenReturn(positionRepo);
        when(signals.getIfAvailable()).thenReturn(signalRepo);
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findPending(anyInt())).thenReturn(List.of());
        when(repo.findSnapshot(anyString())).thenReturn(Optional.empty());
        when(repo.startMonth(any())).thenReturn(YearMonth.of(2026, 10));
        when(repo.rebalanceCompleted(any())).thenReturn(false);
        when(repo.insertSnapshot(anyString(), any(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(true);
        when(index.constituents("sp500")).thenReturn(DataSourceResult.healthy("agora", universe(500)));
        when(bars.fetch(anyList(), eq(231), eq(250), eq(420))).thenAnswer(inv -> series(inv.getArgument(0)));
    }

    private MomentumRankingService service(String instant) {
        return new MomentumRankingService(SETTINGS, repo, index, bars, company, positions, signals,
                telegram, mapper, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static List<IndexConstituent> universe(int n) {
        List<IndexConstituent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new IndexConstituent(String.format("SYN%03d", i),
                    String.format("Synthetic %03d Corp", i), "Technology"));
        }
        return out;
    }

    /** SYNnnn has momentum nnn/10 %; SYN499 carries a −40 % day (suspect). */
    private static Map<String, MomentumBarsClient.Series> series(List<String> symbols) {
        Map<String, MomentumBarsClient.Series> out = new LinkedHashMap<>();
        for (String s : symbols) {
            int i = Integer.parseInt(s.substring(3));
            List<BigDecimal> rocLong = new ArrayList<>(Collections.nCopies(30, BigDecimal.ZERO));
            rocLong.set(8, BigDecimal.valueOf(i).movePointLeft(1));
            List<BigDecimal> roc1 = new ArrayList<>(Collections.nCopies(250, new BigDecimal("0.10")));
            if (i == 499) roc1.set(120, new BigDecimal("-40"));
            out.put(s, new MomentumBarsClient.Series(s, MomentumBarsClient.Status.OK,
                    new BigDecimal("50"), LocalDate.parse("2026-10-30"), rocLong, roc1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ranking(Map<String, Object> out) {
        return (Map<String, Object>) out.get("ranking");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> health(Map<String, Object> out) {
        return (Map<String, Object>) out.get("data_source_health");
    }

    private JsonNode storedPayload(String health) {
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(repo).insertSnapshot(anyString(), any(), any(), anyBoolean(), eq(health), payload.capture());
        return payload.getValue();
    }

    @Test
    void notDueAnswersBeforeAnyAgoraCallAndStoresAMinimalSnapshot() {
        Map<String, Object> out = service("2026-10-29T22:40:00Z").rank("run-nd");

        assertThat(ranking(out)).containsEntry("rebalance_due", false);
        assertThat(health(out)).containsEntry("status", "healthy");
        verifyNoInteractions(index, bars, company);
        verify(repo).insertSnapshot(eq("run-nd"), isNull(), isNull(), eq(false), eq("not_due"), any());
    }

    @Test
    void aSecondCallOfTheSameRunReturnsTheStoredAnswer() {
        when(repo.findSnapshot("run-2")).thenReturn(Optional.of(new MomentumRepository.StoredSnapshot(
                "run-2", null, null, false, "not_due",
                mapper.readTree("{\"llm\": {\"ranking\": {\"rebalance_due\": false, \"reason\": \"x\"}}}"))));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-2");

        assertThat(ranking(out)).containsEntry("reason", "x");
        verify(repo, never()).insertSnapshot(anyString(), any(), any(), anyBoolean(), anyString(), any());
        verifyNoInteractions(index, bars);
    }

    @Test
    void dueRunRanksInChunksOfOneHundredAndStoresTheCompletionView() {
        when(positionRepo.findOpen()).thenReturn(List.of(ExecutorPositionFixtures.momentum(
                ExecutorPositionFixtures.withoutKillLevel(9L, "depot-1", "SYN400", "BUY",
                        BigDecimal.TEN, new BigDecimal("50"), new BigDecimal("32.5"),
                        new BigDecimal("32.5"), 1, null, List.of("k"), "sig-9", "strigoi-momentum",
                        "2026-09-30", null, "OPEN", null, null, null, 0, null, null, null, null,
                        null, null, null, null, null, 0, null, null, null, null, null, null, false,
                        null, "2026-10-01T14:30:00Z"))));
        when(company.profile(anyString())).thenReturn(mapper.readTree("{\"marketCapitalization\": 123456.7}"));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-due");

        verify(bars, times(5)).fetch(anyList(), eq(231), eq(250), eq(420));   // 499 symbols
        Map<String, Object> r = ranking(out);
        assertThat(r).containsEntry("rebalance_due", true).containsEntry("target_month", "2026-10")
                .containsEntry("universe_size", 499).containsEntry("ranked_count", 498);
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) r.get("top");
        @SuppressWarnings("unchecked") List<Map<String, Object>> refill = (List<Map<String, Object>>) r.get("refill");
        assertThat(top).hasSize(10);
        assertThat(refill).hasSize(10);
        assertThat(top.get(0)).containsEntry("symbol", "SYN498").containsEntry("rank", 1)
                .containsEntry("company_name", "Synthetic 498 Corp");
        assertThat(top.get(0).get("market_cap_millions")).isEqualTo(new BigDecimal("123457"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> held = (List<Map<String, Object>>) r.get("held");
        assertThat(held).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("symbol", "SYN400").containsEntry("status", "ranked")
                    .containsEntry("rank", 99).containsEntry("entry_filled", true);
        });
        @SuppressWarnings("unchecked") List<Map<String, Object>> suspects = (List<Map<String, Object>>) r.get("suspects");
        assertThat(suspects).extracting(m -> m.get("symbol")).containsExactly("SYN499");
        assertThat(health(out)).containsEntry("status", "healthy").doesNotContainKey("partial");

        verify(repo).insertSnapshot(eq("run-due"), eq(LocalDate.parse("2026-10-30")),
                eq(YearMonth.of(2026, 10)), eq(true), eq("healthy"), any());
        JsonNode payload = storedPayload("healthy");
        assertThat(payload.path("ranked_all")).hasSize(498);
        assertThat(payload.path("universe")).hasSize(499);
        assertThat(payload.path("offered")).hasSize(20);
        assertThat(payload.path("unranked").path("SYN499").asString()).isEqualTo("data_suspect");
        assertThat(payload.path("llm").path("ranking").path("top")).hasSize(10);
    }

    @Test
    void missingSymbolsAboveTheFloorArePartial() {
        when(bars.fetch(anyList(), eq(231), eq(250), eq(420))).thenAnswer(inv -> {
            Map<String, MomentumBarsClient.Series> s = series(inv.getArgument(0));
            for (int i = 2; i < 12; i++) {
                String sym = String.format("SYN%03d", i);
                if (s.containsKey(sym)) s.put(sym, new MomentumBarsClient.Series(sym,
                        MomentumBarsClient.Status.NO_DATA, null, null, List.of(), List.of()));
            }
            return s;
        });

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-p");

        assertThat(health(out)).containsEntry("status", "healthy").containsEntry("partial", true);
        assertThat(ranking(out)).containsEntry("ranked_count", 488);
        storedPayload("partial");
    }

    @Test
    void aRequestScopeChunkFailureBelowTheFloorIsUnavailable() {
        when(bars.fetch(anyList(), eq(231), eq(250), eq(420)))
                .thenThrow(new AgoraUnavailableException(AgoraUnavailableException.Scope.REQUEST, "chunk failed", null))
                .thenAnswer(inv -> series(inv.getArgument(0)));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-r");

        verify(bars, times(5)).fetch(anyList(), eq(231), eq(250), eq(420));
        assertThat(health(out)).containsEntry("status", "unavailable");
        assertThat((String) health(out).get("detail")).startsWith("ranked 398 of 499");
        storedPayload("unavailable");
    }

    @Test
    void aSourceScopeOutageStopsAtOnceAndIsUnavailable() {
        when(bars.fetch(anyList(), eq(231), eq(250), eq(420)))
                .thenThrow(new AgoraUnavailableException("agora down"));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-s");

        verify(bars, times(1)).fetch(anyList(), anyInt(), anyInt(), anyInt());
        assertThat(health(out)).containsEntry("status", "unavailable");
        verify(repo).insertSnapshot(eq("run-s"), isNull(), eq(YearMonth.of(2026, 10)), eq(true),
                eq("unavailable"), any());
    }

    @Test
    void aTruncatedUniverseIsUnavailableWithoutBarCalls() {
        when(index.constituents("sp500")).thenReturn(DataSourceResult.healthy("agora", universe(479)));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-t");

        assertThat((String) health(out).get("detail")).contains("universe truncated: 479");
        verifyNoInteractions(bars);
    }

    @Test
    void anUnavailableIndexIsUnavailable() {
        when(index.constituents("sp500")).thenReturn(DataSourceResult.unavailable("agora", "agora: down"));

        Map<String, Object> out = service("2026-10-30T22:40:00Z").rank("run-i");

        assertThat(health(out)).containsEntry("status", "unavailable");
        verifyNoInteractions(bars);
    }

    @Test
    void missedOnWeekdayFourRaisesACriticalAlert() {
        Map<String, Object> out = service("2027-01-06T22:40:00Z").rank("run-m");

        verify(telegram).notifyAlert(eq("MOMENTUM"), eq("MOMENTUM_REBALANCE_MISSED"), eq("CRITICAL"),
                contains("2026-12"));
        assertThat(ranking(out)).containsEntry("rebalance_due", false)
                .containsEntry("rebalance_missed", "2026-12");
    }

    @Test
    void theFirstRunMidMonthNeverBackFires() {
        when(repo.startMonth(any())).thenReturn(YearMonth.of(2026, 11));

        Map<String, Object> out = service("2026-11-03T22:40:00Z").rank("run-f");

        assertThat(ranking(out)).containsEntry("rebalance_due", false);
        assertThat((String) ranking(out).get("reason")).contains("before the start month");
        verifyNoInteractions(bars);
    }

    @Test
    void aLostInsertRaceReturnsTheWinnersSnapshot() {
        when(repo.insertSnapshot(anyString(), any(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(false);
        when(repo.findSnapshot("run-race")).thenReturn(Optional.empty(), Optional.of(
                new MomentumRepository.StoredSnapshot("run-race", null, null, false, "not_due",
                        mapper.readTree("{\"llm\": {\"ranking\": {\"winner\": true}}}"))));

        Map<String, Object> out = service("2026-10-29T22:40:00Z").rank("run-race");

        assertThat(ranking(out)).containsEntry("winner", true);
    }
}
