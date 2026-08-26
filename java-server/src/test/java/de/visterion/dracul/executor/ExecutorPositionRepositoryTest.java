package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.executor.broker.RestoredLeg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryTest {

    @Autowired ExecutorPositionRepository repo;
    @Autowired JdbcClient jdbc;

    @Test
    void insertReturnsIdAndFindsOpen() {
        String symbolA = "POS-A-" + UUID.randomUUID();
        String symbolB = "POS-B-" + UUID.randomUUID();

        var posA = new ExecutorPosition(null, "depot-1", symbolA, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS", "GUIDANCE_CUT"), "sig-a", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false);
        var posB = new ExecutorPosition(null, "depot-1", symbolB, "BUY",
                new BigDecimal("5"), new BigDecimal("50.00"), new BigDecimal("45.00"),
                new BigDecimal("47.00"), 1, new BigDecimal("0.8"),
                List.of("STOP_HIT"), "sig-b", "strigoi-insider",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false);

        long idA = repo.insert(posA);
        long idB = repo.insert(posB);

        assertThat(idA).isPositive();
        assertThat(idB).isPositive();
        assertThat(idA).isNotEqualTo(idB);

        assertThat(repo.countOpen()).isGreaterThanOrEqualTo(2);

        var open = repo.findOpen();
        assertThat(open).extracting(ExecutorPosition::symbol).contains(symbolA, symbolB);

        var found = open.stream().filter(p -> p.symbol().equals(symbolA)).findFirst().orElseThrow();
        assertThat(found.killCriteria()).containsExactlyInAnyOrder("EARNINGS_MISS", "GUIDANCE_CUT");
        assertThat(found.entryPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateMaintenanceReflected() {
        String symbol = "POS-MAINT-" + UUID.randomUUID();
        var pos = new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-maint", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false);
        long id = repo.insert(pos);

        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 1,
                new BigDecimal("104"), "stop-9");

        var found = repo.findById(id);
        assertThat(found.highestPrice()).isEqualByComparingTo("110");
        assertThat(found.mfeR()).isEqualByComparingTo("1.6");
        assertThat(found.softConfirmCount()).isEqualTo(1);
        assertThat(found.activeStop()).isEqualByComparingTo("104");
        assertThat(found.stopOrderId()).isEqualTo("stop-9");
    }

    @Test
    void closeMovesOutOfOpen() {
        String symbol = "POS-CLOSE-" + UUID.randomUUID();
        var pos = new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-close", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false);
        long id = repo.insert(pos);

        repo.close(id, new BigDecimal("95"), new BigDecimal("-1.0"), "HARD_STOP", new BigDecimal("10"));

        assertThat(repo.findOpen()).extracting(ExecutorPosition::symbol).doesNotContain(symbol);
        var found = repo.findById(id);
        assertThat(found.status()).isEqualTo("CLOSED");
        assertThat(found.realizedR()).isEqualByComparingTo("-1.0");
        assertThat(found.exitReason()).isEqualTo("HARD_STOP");
        // r_value is the denominator realized_r was actually divided by -- persisted so the row
        // is reconcilable against itself instead of leaving the divisor unrecorded (was always
        // NULL before this fix, see ExecutorPositionRepository#close).
        assertThat(found.rValue()).isEqualByComparingTo("10");
    }

    @Test
    void newColumnsRoundTripAndTranche2Update() {
        long id = repo.insert(openPosition("T2RT" + System.nanoTime()));
        ExecutorPosition p = repo.findById(id);
        assertThat(p.sector()).isEqualTo("Technology");
        assertThat(p.entryDayHigh()).isEqualByComparingTo("105.5");
        assertThat(p.tranche2OrderId()).isNull();
        BigDecimal activeStopBefore = p.activeStop();

        repo.updateTranche2(id, new BigDecimal("20"), new BigDecimal("101.25"), "ord-2", "stop-2");
        ExecutorPosition after = repo.findById(id);
        assertThat(after.tranche()).isEqualTo(2);
        assertThat(after.qty()).isEqualByComparingTo("20");
        assertThat(after.entryPrice()).isEqualByComparingTo("101.25");
        assertThat(after.tranche2OrderId()).isEqualTo("ord-2");
        assertThat(after.tranche2StopOrderId()).isEqualTo("stop-2");
        // active_stop is deliberately NOT touched by updateTranche2 (see ExecutorWebhookController
        // Task 4 notes): tranche 1 keeps its own leg at the old stop, tranche 2 gets a NEW leg at
        // the rounded stop, and StopRatchetService skips tranche()>=2 positions, so nothing ever
        // reconciles them. Writing here would make active_stop wrong for the LARGER (tranche-1)
        // leg instead of the smaller one.
        assertThat(after.activeStop()).isEqualByComparingTo(activeStopBefore);
    }

    @Test
    void updateTranche2ClearsTheCollapseFlag() {
        // stop_legs_collapsed has exactly one job: explain why a two-tranche position names only
        // ONE stop leg. A position that has just been given a second stop leg names two, so
        // leaving the flag set would have the row assert a collapse its own columns contradict --
        // and StopRatchetService reads the pair to tell a legitimately single-legged survivor from
        // a book whose second id is merely unknown.
        long id = repo.insert(openPosition("T2CF" + System.nanoTime()));
        repo.markStopLegsCollapsed(id);
        assertThat(repo.findById(id).stopLegsCollapsed()).isTrue();

        repo.updateTranche2(id, new BigDecimal("20"), new BigDecimal("101.25"), "ord-2", "stop-2");

        ExecutorPosition after = repo.findById(id);
        assertThat(after.stopLegsCollapsed()).isFalse();
        assertThat(after.tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void countEnteredSinceCountsOnlyRecentEntries() {
        Instant before = Instant.now().minusSeconds(5);
        long id = repo.insert(openPosition("CES" + System.nanoTime()));
        assertThat(id).isPositive();

        assertThat(repo.countEnteredSince(before)).isGreaterThanOrEqualTo(1);

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(repo.countEnteredSince(future)).isEqualTo(0);
    }

    @Test
    void recordTrimUpdatesQtyTrimCountAndResetsSoftConfirm() {
        long id = repo.insert(openPosition("TRIM" + System.nanoTime()));
        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 3,
                new BigDecimal("104"), "stop-9");
        assertThat(repo.findById(id).softConfirmCount()).isEqualTo(3);

        repo.recordTrim(id, new BigDecimal("50"), 1);

        var found = repo.findById(id);
        assertThat(found.qty()).isEqualByComparingTo("50");
        assertThat(found.trimCount()).isEqualTo(1);
        assertThat(found.softConfirmCount()).isEqualTo(0);
    }

    @Test
    void recordTrimWritesTheBrokerQuantityNotOurArithmetic() {
        // 23-share position, e.g. OHI @ fraction 0.5: Dracul's own arithmetic floors to 11, but
        // the broker (Agora) floors the other side and reports 12. The broker's number must win.
        long id = repo.insert(openPosition("BROKERQTY" + System.nanoTime()));

        repo.recordTrim(id, new BigDecimal("12"), 1, List.of(), false);

        assertThat(repo.findById(id).qty()).isEqualByComparingTo("12");
    }

    @Test
    void recordTrimRepointsBothStopColumnsViaReplaces() {
        long id = repo.insert(openPositionWithStops("REPOINT" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("5"), new BigDecimal("90")),
                new RestoredLeg("old-2", "new-2", new BigDecimal("5"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, legs, false);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("new-1");
        assertThat(found.tranche2StopOrderId()).isEqualTo("new-2");
    }

    @Test
    void recordTrimLeavesAnUnmatchedColumnAlone() {
        long id = repo.insert(openPositionWithStops("UNMATCHED" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("5"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, legs, false);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("new-1");
        assertThat(found.tranche2StopOrderId()).isEqualTo("old-2");
    }

    @Test
    void collapseNullsTheSecondStopColumnAndSetsTheFlag() {
        long id = repo.insert(openPositionWithStops("COLLAPSE" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("10"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, legs, true);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.tranche2StopOrderId()).isNull();
        assertThat(found.stopLegsCollapsed()).isTrue();
    }

    @Test
    void collapseSurvivorIsMatchedByReplacesNotForcedIntoStopOrderId() {
        // Fix round (whole-branch review, 2026-08-05): Agora's own contract
        // (documentation/exit-tools.md) says a collapse does NOT guarantee exactly one survivor --
        // the allocator fills greedily down tightness order, so more than one leg can come back.
        // recordTrim's collapse branch therefore reconciles every returned leg by `replaces`
        // against BOTH columns, exactly like the non-collapsed branch, rather than forcing
        // whatever came back into stop_order_id unconditionally. Here the single survivor's
        // `replaces` names the OLD tranche2 id, so it lands in tranche2_stop_order_id; stop_order_id
        // (never named as a replaces target) is nulled -- the cancelled "old-1" must not survive
        // anywhere on the row.
        long id = repo.insert(openPositionWithStops("COLLAPSE2" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-2", "survivor-1", new BigDecimal("10"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, legs, true);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isNull();
        assertThat(found.tranche2StopOrderId()).isEqualTo("survivor-1");
        assertThat(found.stopLegsCollapsed()).isTrue();
        // The cancelled "old-1" must not survive on the row anywhere.
        assertThat(found.stopOrderId()).isNotEqualTo("old-1");
        assertThat(found.tranche2StopOrderId()).isNotEqualTo("old-1");
    }

    @Test
    void collapseCanReturnTwoSurvivingLegsBothMatchedByReplaces() {
        // Agora's own contract (documentation/exit-tools.md) is explicit that a collapse does NOT
        // mean "exactly one leg comes back": the allocator fills greedily down tightness order, so
        // e.g. a remainder of 2 against three cancelled stop legs (Dracul's own two, plus a foreign
        // one on the same instrument that Agora's Uic-only filter also cancelled) can return TWO
        // restored legs, and list position has no relation to which of Dracul's columns each one
        // replaces. Taking legs.get(0) unconditionally (the old bug) would silently drop whichever
        // leg didn't land at index 0 -- here both of Dracul's own legs come back, and BOTH columns
        // must be repointed, not just one.
        long id = repo.insert(openPositionWithStops("COLLAPSE3" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-2", "new-2", new BigDecimal("1"), new BigDecimal("90")),
                new RestoredLeg("old-1", "new-1", new BigDecimal("1"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("2"), 1, legs, true);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("new-1");
        assertThat(found.tranche2StopOrderId()).isEqualTo("new-2");
        assertThat(found.stopLegsCollapsed()).isTrue();
    }

    @Test
    void collapseFlagRoundTripsThroughTheRowMapper() {
        long id = repo.insert(openPositionWithStops("ROUNDTRIP" + System.nanoTime(), "old-1", "old-2"));
        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("10"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, legs, true);

        var open = repo.findOpen();
        var found = open.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
        assertThat(found.stopLegsCollapsed()).isTrue();
    }

    @Test
    void clearStopLegNullsOnlyTheColumnNamingThatOrderAndTouchesNothingElse() {
        // A reconcile trim closes the tranche whose stop just filled; the column naming that
        // order must be nulled, or StopRatchetService addresses a dead id by name next run.
        long id = repo.insert(openPositionWithStops("CLEARSTOP" + System.nanoTime(), "old-1", "old-2"));
        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 3,
                new BigDecimal("104"), "old-1");
        ExecutorPosition before = repo.findById(id);

        repo.clearStopLeg(id, "old-1");

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isNull();
        assertThat(found.tranche2StopOrderId()).isEqualTo("old-2");
        assertThat(found.qty()).isEqualByComparingTo(before.qty());
        assertThat(found.trimCount()).isEqualTo(before.trimCount());
        assertThat(found.softConfirmCount()).isEqualTo(3);
    }

    @Test
    void markStopLegsCollapsedSetsOnlyTheFlag() {
        // The pair of writes a reconcile trim makes: null the dead leg, then explain the null.
        long id = repo.insert(openPositionWithStops("COLLAPSEFLAG" + System.nanoTime(), "old-1", "old-2"));
        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 3,
                new BigDecimal("104"), "old-1");
        ExecutorPosition before = repo.findById(id);
        assertThat(before.stopLegsCollapsed()).isFalse();

        repo.clearStopLeg(id, "old-1");
        repo.markStopLegsCollapsed(id);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopLegsCollapsed()).isTrue();
        assertThat(found.stopOrderId()).isNull();
        assertThat(found.tranche2StopOrderId()).isEqualTo("old-2");
        assertThat(found.qty()).isEqualByComparingTo(before.qty());
        assertThat(found.trimCount()).isEqualTo(before.trimCount());
        assertThat(found.softConfirmCount()).isEqualTo(3);
    }

    @Test
    void clearStopLegClearsTheTranche2ColumnAndIgnoresAnUnknownId() {
        long id = repo.insert(openPositionWithStops("CLEARSTOP2" + System.nanoTime(), "old-1", "old-2"));

        repo.clearStopLeg(id, "old-2");
        repo.clearStopLeg(id, "not-a-leg-of-this-position");
        repo.clearStopLeg(id, null);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("old-1");
        assertThat(found.tranche2StopOrderId()).isNull();
    }

    @Test
    void repointStopLegsRepointsAMatchedLegAndLeavesQtyTrimCountSoftConfirmAlone() {
        long id = repo.insert(openPositionWithStops("REPOINTONLY" + System.nanoTime(), "old-1", "old-2"));
        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 3,
                new BigDecimal("104"), "old-1");
        ExecutorPosition before = repo.findById(id);
        assertThat(before.softConfirmCount()).isEqualTo(3);

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("5"), new BigDecimal("90")));
        repo.repointStopLegs(id, legs);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("new-1");
        // Unlike recordTrim, repointStopLegs must not touch qty, trim_count or soft_confirm_count
        // -- no trim happened on the rejection path this method serves.
        assertThat(found.qty()).isEqualByComparingTo(before.qty());
        assertThat(found.trimCount()).isEqualTo(before.trimCount());
        assertThat(found.softConfirmCount()).isEqualTo(3);
    }

    @Test
    void repointStopLegsNullsAColumnWhoseIdIsNotNamedInTheLegs() {
        // Agora's rollback (interleaveRollback) can break at the first failure and report fewer
        // live legs than were cancelled: one stop is matched/repointed, the other is unaccounted
        // for entirely. An unmatched id must be nulled, not left pointing at a cancelled order.
        long id = repo.insert(openPositionWithStops("PARTIALGAP" + System.nanoTime(), "old-1", "old-2"));

        List<RestoredLeg> legs = List.of(
                new RestoredLeg("old-1", "new-1", new BigDecimal("5"), new BigDecimal("90")));
        repo.repointStopLegs(id, legs);

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("new-1");
        assertThat(found.tranche2StopOrderId()).isNull();
    }

    @Test
    void repointStopLegsNullsBothColumnsWhenNoLegsAreReported() {
        // The worst-case LEG_RESTORE_FAILED_UNPROTECTED: Agora reports no live legs at all.
        // Both currently-recorded ids must be nulled rather than left stale.
        long id = repo.insert(openPositionWithStops("NOLEGS" + System.nanoTime(), "old-1", "old-2"));

        repo.repointStopLegs(id, List.of());

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isNull();
        assertThat(found.tranche2StopOrderId()).isNull();
    }

    @Test
    void repointStopLegsDoesNotTouchTheCollapsedFlag() {
        long id = repo.insert(openPositionWithStops("NOCOLLAPSETOUCH" + System.nanoTime(), "old-1", "old-2"));
        List<RestoredLeg> collapseLegs = List.of(
                new RestoredLeg("old-1", "mid-1", new BigDecimal("10"), new BigDecimal("90")));
        repo.recordTrim(id, new BigDecimal("10"), 1, collapseLegs, true);
        assertThat(repo.findById(id).stopLegsCollapsed()).isTrue();

        repo.repointStopLegs(id, List.of(
                new RestoredLeg("mid-1", "final-1", new BigDecimal("10"), new BigDecimal("90"))));

        ExecutorPosition found = repo.findById(id);
        assertThat(found.stopOrderId()).isEqualTo("final-1");
        assertThat(found.stopLegsCollapsed()).isTrue();
    }

    @Test
    void updateAdverseExtremePersists() {
        long id = repo.insert(openPosition("MAE" + System.nanoTime()));

        repo.updateAdverseExtreme(id, new BigDecimal("88.50"));

        assertThat(repo.findById(id).lowestPrice()).isEqualByComparingTo("88.50");
    }

    @Test
    void setEntryExpiresAtPersists() {
        long id = repo.insert(openPosition("GTD" + System.nanoTime()));
        Instant expiry = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        repo.setEntryExpiresAt(id, expiry);

        assertThat(repo.findById(id).entryExpiresAt()).isNotNull();
    }

    @Test
    void findOpenUnfilledPastExpiryReturnsOnlyExpiredOpenPositions() {
        long expiredId = repo.insert(openPosition("EXP-PAST" + System.nanoTime()));
        repo.setEntryExpiresAt(expiredId, Instant.now().minus(1, ChronoUnit.DAYS));

        long futureId = repo.insert(openPosition("EXP-FUTURE" + System.nanoTime()));
        repo.setEntryExpiresAt(futureId, Instant.now().plus(1, ChronoUnit.DAYS));

        long noExpiryId = repo.insert(openPosition("EXP-NONE" + System.nanoTime()));

        var expired = repo.findOpenUnfilledPastExpiry(Instant.now());

        assertThat(expired).extracting(ExecutorPosition::id).contains(expiredId);
        assertThat(expired).extracting(ExecutorPosition::id).doesNotContain(futureId, noExpiryId);
    }

    @Test
    void syncEntryPriceUpdatesOnlyEntryPrice() {
        String symbol = "ACME-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "100.02");

        repo.syncEntryPrice(id, new BigDecimal("100.01"));

        ExecutorPosition p = repo.findById(id);
        assertThat(p.entryPrice()).isEqualByComparingTo("100.01");
        assertThat(p.status()).isEqualTo("OPEN");
    }

    @Test
    void markPendingExitStampsWithoutClosing() {
        String symbol = "ACME-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "100.02");

        repo.markPendingExit(id, "STOP_BREACH", "ord-9", null, Instant.parse("2026-07-16T15:00:00Z"));

        ExecutorPosition p = repo.findById(id);
        assertThat(p.status()).isEqualTo("OPEN");
        assertThat(p.pendingExitReason()).isEqualTo("STOP_BREACH");
        assertThat(p.exitOrderId()).isEqualTo("ord-9");
    }

    @Test
    void secondOpenRowForSameConnectionSymbolFails() {
        String symbol = "ACME-" + UUID.randomUUID();
        insertOpenPosition(symbol, "100.02");

        assertThatThrownBy(() -> insertOpenPosition(symbol.toLowerCase(), "100.04"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findOpenBySymbolReturnsOpenPositionForConnectionAndSymbol() {
        String symbol = "FOBS-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "100.02");

        ExecutorPosition found = repo.findOpenBySymbol("depot-1", symbol);

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.symbol()).isEqualTo(symbol);
        assertThat(found.status()).isEqualTo("OPEN");
    }

    @Test
    void findOpenBySymbolReturnsNullWhenNoOpenPositionMatches() {
        String symbol = "FOBS-NONE-" + UUID.randomUUID();

        assertThat(repo.findOpenBySymbol("depot-1", symbol)).isNull();
    }

    @Test
    void findOpenBySymbolIgnoresClosedPositions() {
        String symbol = "FOBS-CLOSED-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "100.02");
        repo.close(id, new BigDecimal("195"), new BigDecimal("0.2"), "TAKE_PROFIT", null);

        assertThat(repo.findOpenBySymbol("depot-1", symbol)).isNull();
    }

    @Test
    void closeWithSourcePersistsExitPriceSource() {
        String symbol = "ACME-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "100.02");

        repo.close(id, new BigDecimal("98.50"), new BigDecimal("-0.5"), "HARD_STOP", "FILL", null);

        ExecutorPosition p = repo.findById(id);
        assertThat(p.status()).isEqualTo("CLOSED");
        String exitPriceSource = jdbc.sql("SELECT exit_price_source FROM executor_position WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(exitPriceSource).isEqualTo("FILL");
    }

    private long insertOpenPosition(String symbol, String entryPrice) {
        return repo.insert(new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal(entryPrice), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null, false));
    }

    private ExecutorPosition openPosition(String symbol) {
        return new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                "Technology", new BigDecimal("105.5"), null, null, 0, null, null,
                null, null, null, null, false);
    }

    private ExecutorPosition openPositionWithStops(String symbol, String stopOrderId, String tranche2StopOrderId) {
        return new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 2, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, stopOrderId,
                "Technology", new BigDecimal("105.5"), "ord-2", tranche2StopOrderId, 0, null, null,
                null, null, null, null, false);
    }
}
