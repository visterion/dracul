package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-04 §4/§5.3: the completion reads ONLY the snapshot. Offered = SYNA..SYNT (ranks
 *  1..20), ranked_all adds SYNU (21); universe adds SYNW (unranked, missing). */
class MomentumCompletionServiceTest {

    private static final MomentumSettings SETTINGS = new MomentumSettings(10, 10, 3, 480,
            new BigDecimal("0.95"), new BigDecimal("0.025"), 252, 21, new BigDecimal("0.35"),
            new BigDecimal("5"), 1, Set.of(), "depot-1");
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final String RUN = "run-c";
    private static final List<String> OFFERED = List.of("SYNA", "SYNB", "SYNC", "SYND", "SYNE",
            "SYNF", "SYNG", "SYNH", "SYNI", "SYNJ", "SYNK", "SYNL", "SYNM", "SYNN", "SYNO", "SYNP",
            "SYNQ", "SYNR", "SYNS", "SYNT");

    private final MomentumRepository repo = mock(MomentumRepository.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutorPositionRepository> positions = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutorSignalRepository> signals = mock(ObjectProvider.class);

    private MomentumCompletionService service;

    @BeforeEach
    void setUp() {
        when(positions.getIfAvailable()).thenReturn(positionRepo);
        when(signals.getIfAvailable()).thenReturn(signalRepo);
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findPending(anyInt())).thenReturn(List.of());
        when(positionRepo.markRebalanceExit(anyLong(), anyString(), any())).thenReturn(true);
        when(positionRepo.clearRebalanceExit(anyLong(), anyString())).thenReturn(true);
        when(repo.findSnapshot(RUN)).thenReturn(Optional.of(snapshot("healthy", true)));
        when(repo.lastDueSnapshotPayloads(any(), eq(3))).thenReturn(List.of());
        when(repo.markRebalanced(any(), anyString())).thenReturn(true);
        service = new MomentumCompletionService(SETTINGS, repo, positions, signals,
                Clock.fixed(Instant.parse("2026-10-30T22:45:00Z"), ZoneOffset.UTC));
    }

    private MomentumRepository.StoredSnapshot snapshot(String health, boolean due) {
        ObjectNode p = mapper.createObjectNode();
        p.putObject("llm");
        p.put("ranked_count", 21);
        ArrayNode offered = p.putArray("offered");
        ArrayNode rankedAll = p.putArray("ranked_all");
        ArrayNode universe = p.putArray("universe");
        for (int i = 0; i < OFFERED.size(); i++) {
            String s = OFFERED.get(i);
            ObjectNode o = offered.addObject();
            o.put("rank", i + 1);
            o.put("symbol", s);
            o.put("company_name", "Synthetic " + s.charAt(3) + " Corp");
            o.put("momentum_12_1_pct", new BigDecimal("90.04").subtract(BigDecimal.valueOf(i)));
            o.put("return_1m_pct", new BigDecimal("4.25"));
            rankedAll.addObject().put("symbol", s).put("rank", i + 1).put("momentum_pct", 90 - i);
            universe.add(s);
        }
        rankedAll.addObject().put("symbol", "SYNU").put("rank", 21).put("momentum_pct", 60);
        universe.add("SYNU");
        universe.add("SYNW");
        p.putObject("unranked").put("SYNW", "missing").put("SYNY", "data_suspect");
        universe.add("SYNY");
        return new MomentumRepository.StoredSnapshot(RUN, null, due ? OCT.toString() : null, due, health, p);
    }

    private static ExecutorPosition row(long id, String symbol, ExitProfile profile, boolean filled,
            String rebalanceExitAt) {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(id, "depot-1", symbol, "BUY",
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("65"), new BigDecimal("65"), 1,
                null, List.of("k"), "sig-" + id, "agent", "2026-09-30", null, "OPEN", null, null,
                null, 0, null, null, null, null, null, null, null, null, null, 0, null, null, null,
                null, null, null, false, null, filled ? "2026-10-01T14:30:00Z" : null);
        return ExecutorPositionFixtures.withRebalanceExitAt(
                ExecutorPositionFixtures.withProfileFields(base, profile, null, null, null, false),
                rebalanceExitAt);
    }

    private static ExecutorSignal pending(String symbol, String mechanism) {
        return new ExecutorSignal("ps-" + symbol, "hunter", "v1", symbol, "BUY", 0.5, mechanism,
                List.of("k"), "1m", null, "PENDING", "2026-10-29T22:45:00Z");
    }

    private JsonNode output(String json) {
        return mapper.readTree(json);
    }

    private static List<String> symbols(List<Prey> prey) {
        return prey.stream().map(Prey::symbol).toList();
    }

    /** Steps 3, 4, 6: invalid vetoes dropped, held-elsewhere skipped, refill in rank order, held
     *  and pending MOMENTUM names get no new prey. */
    @Test
    void finalTopTenAndEntries() {
        when(positionRepo.findOpen()).thenReturn(List.of(
                row(1L, "SYNA", ExitProfile.MOMENTUM, true, null),         // held, in final
                row(2L, "SYND", ExitProfile.STANDARD, true, null)));       // held elsewhere
        when(signalRepo.findPending(anyInt())).thenReturn(List.of(
                pending("SYNE", "MOMENTUM_12_1"),                          // pending momentum
                pending("SYNF", "PEAD")));                                 // pending elsewhere

        var result = service.complete(output("""
                {"prey": [], "vetoes": [
                  {"symbol": "synb", "reason": "synthetic takeover pending"},
                  {"symbol": "SYNX", "reason": "not offered"},
                  {"symbol": "SYNC", "reason": " "}]}
                """), RUN, 0);

        // final: A C E G H I J K L M (B vetoed, D and F elsewhere); entries: final minus A (held) minus E (pending)
        assertThat(symbols(result.prey())).containsExactly("SYNC", "SYNG", "SYNH", "SYNI", "SYNJ",
                "SYNK", "SYNL", "SYNM");
        assertThat(result.notes()).containsEntry("vetoed", 1).containsEntry("veto_invalid", 2)
                .containsEntry("held_elsewhere", 2).containsEntry("refilled", 3)
                .containsEntry("short_book", 0).containsEntry("suspect_excluded", 1);
        verify(repo).markRebalanced(OCT, RUN);
    }

    @Test
    void codeBuiltPreyFields() {
        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        Prey first = result.prey().get(0);
        assertThat(first.symbol()).isEqualTo("SYNA");
        assertThat(first.companyName()).isEqualTo("Synthetic A Corp");
        assertThat(first.anomalyType()).isEqualTo("MOMENTUM_12_1");
        assertThat(first.confidence()).isEqualTo(0.50);
        assertThat(first.thesis()).isEqualTo("12-1 momentum rank 1 of 21: +90.0 % (t−252..t−21)");
        assertThat(first.signals()).containsExactly("rank 1 of 21", "momentum_12_1 +90.0 %",
                "return_1m +4.3 %");
        assertThat(first.risks()).containsExactly("momentum crash", "data: spin-off artefacts possible");
        assertThat(first.killCriteria()).containsExactly(
                "managed by exit profile MOMENTUM: monthly rebalance exit or emergency stop");
        assertThat(first.horizon()).isEqualTo(MomentumSettings.HORIZON).isEqualTo("1m");
        assertThat(first.discoveredBy()).isEqualTo("strigoi-momentum");
        assertThat(first.discoveredAt()).isEqualTo("2026-10-30T22:45:00Z");
        assertThat(first.killCloseBelow()).isNull();
        assertThat(result.prey()).hasSize(10);
    }

    /** Step 5 (§4): ranked-not-final → flag; not in universe → flag; final → clear; unranked →
     *  carried; unfilled → left to its GTD expiry. The month is marked only afterwards. */
    @Test
    void rebalanceFlagsAndClearing() {
        when(positionRepo.findOpen()).thenReturn(List.of(
                row(1L, "SYNA", ExitProfile.MOMENTUM, true, "2026-09-30 22:40:00+00"),   // final -> clear
                row(2L, "SYNU", ExitProfile.MOMENTUM, true, null),                       // rank 21 -> exit
                row(3L, "SYNZ", ExitProfile.MOMENTUM, true, null),                       // left the index -> exit
                row(4L, "SYNW", ExitProfile.MOMENTUM, true, null),                       // unranked -> carried
                row(5L, "SYNP", ExitProfile.MOMENTUM, false, null)));                    // rank 16, unfilled

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        verify(positionRepo).clearRebalanceExit(1L, "depot-1");
        verify(positionRepo).markRebalanceExit(eq(2L), eq("depot-1"), any());
        verify(positionRepo).markRebalanceExit(eq(3L), eq("depot-1"), any());
        verify(positionRepo, never()).markRebalanceExit(eq(4L), anyString(), any());
        verify(positionRepo, never()).markRebalanceExit(eq(5L), anyString(), any());
        assertThat(result.notes()).containsEntry("rebalance_exits", 2).containsEntry("flags_cleared", 1)
                .containsEntry("not_in_universe", 1).containsEntry("held_unranked", 1)
                .containsEntry("carried_too_long", 0);
        assertThat(symbols(result.prey())).doesNotContain("SYNA", "SYNP");
        InOrder order = inOrder(positionRepo, repo);
        order.verify(positionRepo).markRebalanceExit(eq(2L), eq("depot-1"), any());
        order.verify(repo).markRebalanced(OCT, RUN);
    }

    /** §4: unranked in the last three due snapshots (current included) → exited. */
    @Test
    void aNameUnrankedForAThirdRebalanceIsExited() {
        when(positionRepo.findOpen()).thenReturn(List.of(row(4L, "SYNW", ExitProfile.MOMENTUM, true, null)));
        JsonNode unrankedW = mapper.readTree("{\"unranked\": {\"SYNW\": \"too_few_bars\"}}");
        when(repo.lastDueSnapshotPayloads(OCT, 3)).thenReturn(List.of(unrankedW, unrankedW, unrankedW));

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        verify(positionRepo).markRebalanceExit(eq(4L), eq("depot-1"), any());
        assertThat(result.notes()).containsEntry("carried_too_long", 1).containsEntry("rebalance_exits", 1);
    }

    @Test
    void twoUnrankedRebalancesAreStillCarried() {
        when(positionRepo.findOpen()).thenReturn(List.of(row(4L, "SYNW", ExitProfile.MOMENTUM, true, null)));
        JsonNode unrankedW = mapper.readTree("{\"unranked\": {\"SYNW\": \"too_few_bars\"}}");
        JsonNode rankedW = mapper.readTree("{\"unranked\": {}}");
        when(repo.lastDueSnapshotPayloads(OCT, 3)).thenReturn(List.of(unrankedW, unrankedW, rankedW));

        service.complete(output("{\"prey\": []}"), RUN, 0);

        verify(positionRepo, never()).markRebalanceExit(anyLong(), anyString(), any());
    }

    /** Ruling m8: every final name already held as MOMENTUM -> only the flags change, no new
     *  prey, and the month is still marked. */
    @Test
    void exitsWithoutEntries() {
        List<ExecutorPosition> held = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            held.add(row(10L + i, OFFERED.get(i), ExitProfile.MOMENTUM, true, null));
        }
        held.add(row(2L, "SYNU", ExitProfile.MOMENTUM, true, null));   // rank 21 -> exit
        when(positionRepo.findOpen()).thenReturn(held);

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        assertThat(result.prey()).isEmpty();
        verify(positionRepo).markRebalanceExit(eq(2L), eq("depot-1"), any());
        verify(positionRepo, never()).markRebalanceExit(eq(10L), anyString(), any());
        assertThat(result.notes()).containsEntry("rebalance_exits", 1).containsEntry("short_book", 0);
        verify(repo).markRebalanced(OCT, RUN);
    }

    /** Fix round 1: a stray pending signal of another hunter on a name momentum already holds
     *  must not push the holding out of the final list (and into a rebalance exit). */
    @Test
    void aHeldMomentumNameIsNeverHeldElsewhere() {
        when(positionRepo.findOpen()).thenReturn(List.of(row(1L, "SYNA", ExitProfile.MOMENTUM, true, null)));
        when(signalRepo.findPending(anyInt())).thenReturn(List.of(pending("SYNA", "PEAD")));

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        verify(positionRepo, never()).markRebalanceExit(anyLong(), anyString(), any());
        assertThat(result.notes()).containsEntry("held_elsewhere", 0).containsEntry("rebalance_exits", 0)
                .containsEntry("refilled", 0);
        assertThat(symbols(result.prey())).doesNotContain("SYNA").hasSize(9);
        verify(repo).markRebalanced(OCT, RUN);
    }

    /** Fix round 1: a re-ranked name whose flatten is already submitted is not "cleared". */
    @Test
    void aSubmittedFlattenIsNotCountedAsCleared() {
        when(positionRepo.findOpen()).thenReturn(List.of(ExecutorPositionFixtures.withPendingExit(
                row(1L, "SYNA", ExitProfile.MOMENTUM, true, "2026-09-30 22:40:00+00"), "HARD_REBALANCE")));

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        assertThat(result.notes()).containsEntry("flags_cleared", 0).containsEntry("clear_too_late", 1);
        assertThat(MomentumCompletionService.WARN_KEYS).contains("clear_too_late");
    }

    /** §5.3: a failure in step 5 leaves the month open — no prey, no mark. */
    @Test
    void aFlagFailureLeavesTheMonthOpen() {
        when(positionRepo.findOpen()).thenReturn(List.of(row(2L, "SYNU", ExitProfile.MOMENTUM, true, null)));
        when(positionRepo.markRebalanceExit(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        var result = service.complete(output("{\"prey\": []}"), RUN, 0);

        assertThat(result.prey()).isEmpty();
        assertThat(result.notes()).containsEntry("rebalance_exit_failed", 1);
        verify(repo, never()).markRebalanced(any(), any());
    }

    @Test
    void aShortBookWhenTooManyNamesAreStruck() {
        StringBuilder vetoes = new StringBuilder();
        for (String s : OFFERED.subList(0, 15)) {
            if (vetoes.length() > 0) vetoes.append(',');
            vetoes.append("{\"symbol\": \"").append(s).append("\", \"reason\": \"synthetic\"}");
        }

        var result = service.complete(output("{\"prey\": [], \"vetoes\": [" + vetoes + "]}"), RUN, 0);

        assertThat(result.prey()).hasSize(5);
        assertThat(result.notes()).containsEntry("short_book", 5).containsEntry("vetoed", 15);
    }

    @Test
    void noSnapshotNotDueUnavailableAndNoExecutorAreNoOps() {
        when(repo.findSnapshot("run-none")).thenReturn(Optional.empty());
        assertThat(service.complete(output("{\"prey\": []}"), "run-none", 0).notes())
                .containsEntry("no_snapshot", 1);

        when(repo.findSnapshot(RUN)).thenReturn(Optional.of(snapshot("not_due", false)));
        assertThat(service.complete(output("{\"prey\": []}"), RUN, 2).prey()).isEmpty();

        when(repo.findSnapshot(RUN)).thenReturn(Optional.of(snapshot("unavailable", true)));
        assertThat(service.complete(output("{\"prey\": []}"), RUN, 0).prey()).isEmpty();

        when(positions.getIfAvailable()).thenReturn(null);
        assertThat(service.complete(output("{\"prey\": []}"), RUN, 0).notes())
                .containsEntry("executor_disabled", 1);

        verify(repo, never()).markRebalanced(any(), any());
        verify(positionRepo, never()).markRebalanceExit(anyLong(), anyString(), any());
    }

    @Test
    void llmPreyAreCountedAsIgnored() {
        var result = service.complete(output("{\"prey\": [{\"symbol\": \"SYNZ\"}]}"), RUN, 1);
        assertThat(result.notes()).containsEntry("llm_prey_ignored", 1);
        assertThat(symbols(result.prey())).doesNotContain("SYNZ");
    }
}
